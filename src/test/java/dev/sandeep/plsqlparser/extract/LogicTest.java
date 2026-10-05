package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.parse.Discover;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LogicTest {
    private static StructureModel model(String... sources) {
        List<PlSqlParserFacade.ParsedUnit> parsed = new ArrayList<>();
        int i = 0;
        for (String s : sources) {
            var u = PlSqlParserFacade.parseText(Path.of("f" + (i++) + ".sql"), "primary", s, List.of());
            assertTrue(u.ok(), () -> u.errors().toString());
            parsed.add(u);
        }
        return StructureBuilder.build(parsed);
    }

    private static Routine routine(StructureModel m, String id) {
        for (PlsqlUnit u : m.units) for (Routine r : u.decls.routines) if (id.equals(r.id) && r.hasBody) return r;
        throw new AssertionError("no routine " + id);
    }

    private static List<LogicStep> flat(List<LogicStep> in) {
        List<LogicStep> out = new ArrayList<>();
        for (LogicStep s : in) {
            out.add(s);
            out.addAll(flat(s.children));
        }
        return out;
    }

    private static List<String> kinds(Routine r) {
        return flat(r.outline).stream().map(s -> s.kind).toList();
    }

    /** One routine that uses (almost) every statement kind of the grammar. */
    private static final String EVERYTHING = """
            create or replace procedure everything(p_n in number, p_s in varchar2) is
              type t_tab is table of number;
              l_tab t_tab := t_tab(1, 2);
              l_n number := 0;
              cursor c1 is select empno from emp;
              l_e c1%rowtype;
              e_mine exception;
              pragma exception_init(e_mine, -20100);
            begin
              <<top>>
              l_n := p_n + 1;
              if l_n > 1 then
                null;
              elsif l_n = 1 then
                l_n := 0;
              else
                l_n := -1;
              end if;
              case p_s when 'a' then l_n := 1; when 'b' then l_n := 2; else l_n := 3; end case;
              case when l_n > 5 then l_n := 5; end case;
              loop exit when l_n > 10; l_n := l_n + 1; continue when l_n = 3; end loop;
              while l_n < 20 loop l_n := l_n + 1; end loop;
              for i in 1 .. 3 loop l_n := l_n + i; end loop;
              for r in (select empno from emp) loop insert into audit_t values (r.empno); end loop;
              forall i in 1 .. l_tab.count update emp set sal = sal + 1 where empno = l_tab(i);
              l_tab.delete(1);
              open c1; fetch c1 into l_e; close c1;
              select count(*) into l_n from emp;
              execute immediate 'truncate table tmp_t';
              savepoint sp1;
              declare
                l_x number;
              begin
                l_x := 1;
              exception
                when no_data_found or too_many_rows then null;
                when e_mine then raise;
              end;
              begin
                raise e_mine;
              exception
                when others then rollback to sp1; raise_application_error(-20001, 'boom');
              end;
              helper;
              commit;
              goto the_end;
              <<the_end>>
              return;
            end;
            /
            create or replace procedure helper is begin null; end;
            /
            create or replace function pipe_f return sys.odcinumberlist pipelined is
            begin
              pipe row (1);
              return;
            end;
            /
            """;

    @Test
    void everyStatementKindBecomesAStep_noUnknownsNoMismatch() {
        StructureModel m = model(EVERYTHING);
        Routine r = routine(m, "EVERYTHING");
        List<String> k = kinds(r);
        for (String want : List.of("ASSIGN", "IF", "THEN", "ELSIF", "ELSE", "CASE", "WHEN", "LOOP", "WHILE_LOOP", "FOR_LOOP", "CURSOR_FOR_LOOP",
                "FORALL", "EXIT", "CONTINUE", "NULL", "COLLECTION_OP", "OPEN_CURSOR", "FETCH", "CLOSE_CURSOR", "SQL", "DYNAMIC_SQL", "SAVEPOINT",
                "BLOCK", "EXCEPTION_SECTION", "HANDLER", "RAISE", "ROLLBACK", "CALL", "COMMIT", "GOTO", "RETURN", "LABEL"))
            assertTrue(k.contains(want), want + " missing in " + k);
        assertFalse(k.contains("UNKNOWN"));
        assertTrue(m.issues.stream().noneMatch(i -> i.code().equals("outline-mismatch")), m.issues::toString);
        assertTrue(kinds(routine(m, "PIPE_F")).contains("PIPE_ROW"));
    }

    @Test
    void outlineNestingAndLinks() {
        Routine r = routine(model(EVERYTHING), "EVERYTHING");
        LogicStep iff = r.outline.stream().filter(s -> s.kind.equals("IF")).findFirst().orElseThrow();
        assertEquals("l_n > 1", iff.text);
        assertEquals(List.of("THEN", "ELSIF", "ELSE"), iff.children.stream().map(s -> s.kind).toList());
        LogicStep cursorLoop = flat(r.outline).stream().filter(s -> s.kind.equals("CURSOR_FOR_LOOP")).findFirst().orElseThrow();
        assertTrue(cursorLoop.refs.contains("READ EMP"), cursorLoop.refs::toString);
        LogicStep insert = cursorLoop.children.get(0);
        assertTrue(insert.refs.contains("INSERT AUDIT_T"), insert.refs::toString);
        LogicStep call = flat(r.outline).stream().filter(s -> s.kind.equals("CALL") && s.text.equals("helper")).findFirst().orElseThrow();
        assertTrue(call.refs.contains("calls HELPER"), call.refs::toString);
        LogicStep forall = flat(r.outline).stream().filter(s -> s.kind.equals("FORALL")).findFirst().orElseThrow();
        assertTrue(forall.children.get(0).refs.contains("UPDATE EMP"));
    }

    @Test
    void exceptionContractAndEffectsAndMetrics() {
        Routine r = routine(model(EVERYTHING), "EVERYTHING");
        ExceptionContract ec = r.exceptionContract;
        assertTrue(ec.raises.stream().anyMatch(x -> x.how().equals("RAISE") && "e_mine".equals(x.exception())));
        assertTrue(ec.raises.stream().anyMatch(x -> x.how().equals("RE_RAISE")));
        assertTrue(ec.raises.stream().anyMatch(x -> x.how().equals("RAISE_APPLICATION_ERROR") && Integer.valueOf(-20001).equals(x.errorCode())));
        assertEquals(Set.of("NO_DATA_FOUND", "TOO_MANY_ROWS"), ec.implicit);
        assertEquals(3, ec.handlers.size());
        assertTrue(ec.handlers.stream().anyMatch(h -> h.others() && h.reraises() && h.endsTransaction()));
        assertTrue(ec.handlers.stream().anyMatch(h -> h.exceptions().contains("no_data_found") && h.swallows() && h.onlyNull()));

        SideEffects fx = r.sideEffects;
        assertEquals(1, fx.commits.size());
        assertEquals(1, fx.rollbacks.size());
        assertEquals(1, fx.savepoints.size());
        assertEquals(1, fx.dynamicSqlLines.size());
        assertTrue(fx.ddl.get(0).startsWith("TRUNCATE"), fx.ddl::toString);

        Metrics mx = r.metrics;
        assertEquals(5, mx.loops);
        assertTrue(mx.cyclomatic > 10, () -> "cyclomatic " + mx.cyclomatic);
        assertTrue(mx.statements > 30, () -> "statements " + mx.statements);
        assertTrue(mx.maxNesting >= 2, () -> "nesting " + mx.maxNesting);
    }

    @Test
    void packageStateSequencesAndExternalIo() {
        StructureModel m = model("""
                create or replace package st as procedure p; end st;
                /
                create or replace package body st as
                  g_count number := 0;
                  g_name varchar2(10);
                  procedure p is
                    l_id number;
                    l_f utl_file.file_type;
                  begin
                    g_count := g_count + 1;
                    select my_seq.nextval into l_id from dual;
                    select count(*) into g_count from t;
                    utl_file.fclose(l_f);
                    dbms_output.put_line(g_name);
                  end;
                end st;
                /
                """);
        SideEffects fx = routine(m, "ST.P").sideEffects;
        assertEquals(Set.of("g_count"), fx.packageStateWrites);
        assertEquals(Set.of("g_count", "g_name"), fx.packageStateReads);
        assertEquals(Set.of("MY_SEQ"), fx.sequences);
        assertEquals(Set.of("UTL_FILE.FCLOSE"), fx.externalIo.get("FILE_IO"));
        assertEquals(Set.of("DBMS_OUTPUT.PUT_LINE"), fx.externalIo.get("CONSOLE"));
    }

    @Test
    void localVariableShadowsPackageState() {
        StructureModel m = model("""
                create or replace package sh as procedure p; end sh;
                /
                create or replace package body sh as
                  g_x number;
                  procedure p is g_x number; begin g_x := 1; end;
                end sh;
                /
                """);
        assertTrue(routine(m, "SH.P").sideEffects.packageStateWrites.isEmpty());
    }

    @Test
    void riskRulesFire() {
        StructureModel m = model("""
                create or replace procedure risky(p_t in varchar2, p_id in number) is
                  l_n number;
                  l_ids sys.odcinumberlist;
                begin
                  for r in 1 .. 3 loop
                    update emp set sal = sal + 1 where empno = r;
                    select count(*) into l_n from dept where deptno = r;
                  end loop;
                  execute immediate 'delete from ' || p_t || ' where id = ' || p_id;
                  select empno bulk collect into l_ids from emp;
                  select /*+ full(e) */ sal into l_n from emp e where empno = p_id;
                  commit;
                  raise_application_error(-500, 'bad');
                exception
                  when others then null;
                end;
                /
                create or replace function safe_f return number is begin return 1; exception when others then return -1; end;
                /
                create or replace procedure sleepy is begin dbms_lock.sleep(1); utl_http.begin_request('x'); end;
                /
                """);
        Set<String> codes = new HashSet<>();
        for (Risk r : m.risks) codes.add(r.code());
        for (String want : List.of("ROW_BY_ROW_DML", "SQL_IN_LOOP", "DYNAMIC_SQL_CONCATENATION", "UNBOUNDED_BULK_COLLECT", "OPTIMIZER_HINT",
                "COMMIT_IN_ROUTINE", "APP_ERROR_CODE_OUT_OF_RANGE", "WHEN_OTHERS_NULL", "WHEN_OTHERS_RETURNS_DEFAULT", "EXTERNAL_SIDE_EFFECT"))
            assertTrue(codes.contains(want), want + " not in " + codes);
        assertTrue(m.risks.stream().anyMatch(r -> r.code().equals("DYNAMIC_SQL_CONCATENATION") && r.severity().equals("HIGH")));
        assertEquals("HIGH", m.risks.get(0).severity(), "risks are sorted most severe first");
    }

    @Test
    void triggerBodyHasOutlineAndEffects() {
        StructureModel m = model("""
                create or replace trigger trg_t before insert on t for each row
                begin
                  if :new.id is null then
                    select my_seq.nextval into :new.id from dual;
                  end if;
                  insert into t_hist values (:new.id);
                end;
                /
                """);
        PlsqlUnit trg = m.units.get(0);
        assertEquals(List.of("IF", "THEN", "SQL", "SQL"), flat(trg.outline).stream().map(s -> s.kind).toList());
        assertTrue(trg.sideEffects.sequences.contains("MY_SEQ"));
    }

    @Test
    void realWorldCorpusOutlinesAreComplete() throws Exception {
        Path dir = Path.of("corpus", "real");
        assumeTrue(Files.isDirectory(dir));
        StructureModel m = StructureBuilder.build(PlSqlParserFacade.parseFiles(Discover.files(dir), Map.of(), new int[]{19, 0}));
        assertTrue(m.issues.stream().noneMatch(i -> i.code().equals("outline-mismatch")), () -> m.issues.toString());
        for (PlsqlUnit u : m.units) check(u.decls.routines);
    }

    private static void check(List<Routine> rs) {
        for (Routine r : rs) {
            assertFalse(kinds(r).contains("UNKNOWN"), r.id + " has UNKNOWN steps");
            if (r.hasBody) assertTrue(r.metrics.statements > 0 || r.outline.isEmpty(), r.id);
            check(r.locals.routines);
        }
    }
}
