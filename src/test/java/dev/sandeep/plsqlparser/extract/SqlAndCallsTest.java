package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlAndCallsTest {
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
        for (PlsqlUnit u : m.units) {
            Routine r = find(u.decls.routines, id);
            if (r != null) return r;
        }
        throw new AssertionError("no routine " + id);
    }

    private static Routine find(List<Routine> rs, String id) {
        for (Routine r : rs) {
            if (id.equals(r.id) && (r.hasBody || r.forwardDeclaration == false && !r.sql.isEmpty() || r.hasBody)) return r;
            Routine n = find(r.locals.routines, id);
            if (n != null) return n;
        }
        return null;
    }

    private static TableRef table(SqlStatement s, String key) {
        return s.tables.stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError(key + " in " + s.tables));
    }

    private static final String DML = """
            create or replace procedure p(p_id in number, p_name in varchar2) is
              l_x number;
            begin
              select e.sal, d.dname into l_x, l_x from emp e join scott.dept d on d.deptno = e.deptno where e.empno = p_id;
              insert into audit_log (id, msg) values (p_id, p_name);
              update emp set sal = sal * 2, comm = 0 where empno = p_id;
              delete from tmp_t where id = p_id;
              merge into tgt t using src s on (t.id = s.id)
                when matched then update set t.v = s.v
                when not matched then insert (id, v) values (s.id, s.v);
              for r in (select col1 from big_t) loop
                insert into row_by_row values (r.col1);
              end loop;
              with c as (select 1 x from dual) select x into l_x from c;
              lock table locked_t in exclusive mode;
            end;
            /
            """;

    @Test
    void extractsTablesAccessAndColumns() {
        Routine r = routine(model(DML), "P");
        List<SqlStatement> s = r.sql;
        TableRef emp = table(s.get(0), "EMP");
        assertEquals(Set.of("READ"), emp.access);
        assertTrue(emp.readColumns.containsAll(Set.of("sal", "deptno", "empno")), emp.readColumns::toString);
        assertTrue(table(s.get(0), "SCOTT.DEPT").readColumns.contains("dname"));
        assertTrue(s.get(0).intoTargets.contains("l_x"));
        assertTrue(s.get(0).binds.contains("p_id"));

        TableRef audit = table(s.get(1), "AUDIT_LOG");
        assertEquals(Set.of("INSERT"), audit.access);
        assertEquals(Set.of("id", "msg"), audit.writeColumns);

        TableRef upd = table(s.get(2), "EMP");
        assertEquals(Set.of("UPDATE"), upd.access);
        assertEquals(Set.of("sal", "comm"), upd.writeColumns);

        assertEquals(Set.of("DELETE"), table(s.get(3), "TMP_T").access);

        TableRef tgt = table(s.get(4), "TGT");
        assertEquals(Set.of("MERGE"), tgt.access);
        assertTrue(tgt.writeColumns.containsAll(Set.of("v", "id")));
        assertEquals(Set.of("READ"), table(s.get(4), "SRC").access);

        assertTrue(r.tablesWritten.containsAll(Set.of("AUDIT_LOG", "EMP", "TMP_T", "TGT", "ROW_BY_ROW")));
        assertTrue(r.tablesRead.containsAll(Set.of("EMP", "SCOTT.DEPT", "SRC", "BIG_T", "LOCKED_T")));
    }

    @Test
    void ctesAndDualAreNotTables_loopFlagAndLock() {
        Routine r = routine(model(DML), "P");
        SqlStatement cte = r.sql.stream().filter(x -> x.text.startsWith("with c")).findFirst().orElseThrow();
        assertTrue(cte.tables.isEmpty(), cte.tables::toString);
        SqlStatement inLoop = r.sql.stream().filter(x -> x.text.contains("row_by_row")).findFirst().orElseThrow();
        assertTrue(inLoop.inLoop);
        SqlStatement header = r.sql.stream().filter(x -> x.kind.equals("CURSOR_LOOP")).findFirst().orElseThrow();
        assertFalse(header.inLoop);
        assertTrue(r.sql.stream().anyMatch(x -> x.kind.equals("LOCK")));
    }

    @Test
    void forallIsBulkNotInLoop() {
        Routine r = routine(model("""
                create or replace procedure p(t in sys.odcinumberlist) is
                begin
                  forall i in 1 .. t.count insert into bulk_t values (t(i));
                end;
                /
                """), "P");
        SqlStatement s = r.sql.get(0);
        assertTrue(s.bulk);
        assertFalse(s.inLoop);
        assertEquals(Set.of("INSERT"), table(s, "BULK_T").access);
    }

    @Test
    void dynamicSqlLiteralPartialAndUnknown() {
        Routine r = routine(model("""
                create or replace procedure p(p_tab in varchar2, p_stmt in varchar2) is
                begin
                  execute immediate 'truncate table ' || p_tab;
                  execute immediate 'insert into dyn_a select * from dyn_b where x = :1' using 1;
                  execute immediate 'delete from ' || p_tab || ' where id in (select id from dyn_c)';
                  execute immediate p_stmt;
                end;
                /
                """), "P");
        List<SqlStatement> s = r.sql;
        assertEquals("PARTIAL", s.get(0).dynamicConfidence);
        assertEquals("TRUNCATE", s.get(0).dynamicKind);
        assertEquals("LITERAL", s.get(1).dynamicConfidence);
        assertEquals(Set.of("INSERT"), table(s.get(1), "DYN_A").access);
        assertEquals(Set.of("READ"), table(s.get(1), "DYN_B").access);
        assertEquals("PARTIAL", s.get(2).dynamicConfidence);
        assertEquals("INFERRED", table(s.get(2), "DYN_C").confidence);
        assertEquals("UNKNOWN", s.get(3).dynamicConfidence);
        assertTrue(s.stream().allMatch(x -> x.dynamic));
    }

    private static final String PKG_SPEC = """
            create or replace package a_pkg as
              c_const constant number := 1;
              procedure entry(p in number);
              function f(x in number) return number;
              function f(x in varchar2) return varchar2;
            end a_pkg;
            /
            """;
    private static final String PKG_BODY = """
            create or replace package body a_pkg as
              type t_tab is table of number index by pls_integer;
              g_tab t_tab;
              procedure helper(p in number) is begin null; end;
              procedure entry(p in number) is
                procedure inner_p is begin helper(1); end;
                l_n number;
              begin
                inner_p;
                helper(p);
                l_n := f(p) + f('a'||'b');
                l_n := g_tab(1) + g_tab.count + c_const + a_pkg.c_const;
                dbms_output.put_line('x');
                other_pkg.do_it(1);
                b_pkg.run;
                missing_proc(2);
                for i in 1 .. 3 loop helper(i); end loop;
                raise_application_error(-20001, 'x');
                l_n := nvl(l_n, 0) + length('x');
              end;
              function f(x in number) return number is begin return x; end;
              function f(x in varchar2) return varchar2 is begin return x; end;
            end a_pkg;
            /
            create or replace package b_pkg as procedure run; end b_pkg;
            /
            create or replace package body b_pkg as procedure run is begin a_pkg.entry(1); end; end b_pkg;
            /
            """;

    @Test
    void resolvesLocalInternalBuiltinExternalAndIgnoresNonCalls() {
        StructureModel m = model(PKG_SPEC, PKG_BODY);
        Routine entry = routine(m, "A_PKG.ENTRY");
        Map<String, CallSite> byCallee = new LinkedHashMap<>();
        for (CallSite c : entry.calls) byCallee.putIfAbsent(c.callee.toLowerCase(), c);

        assertEquals(CallSite.LOCAL, byCallee.get("inner_p").resolution);
        assertEquals("A_PKG.ENTRY>INNER_P", byCallee.get("inner_p").targetId);
        assertEquals(CallSite.INTERNAL, byCallee.get("helper").resolution);
        assertEquals("A_PKG.HELPER", byCallee.get("helper").targetId);
        assertEquals(CallSite.INTERNAL, byCallee.get("f").resolution);
        assertEquals(CallSite.BUILTIN, byCallee.get("dbms_output.put_line").resolution);
        assertEquals("DBMS_OUTPUT", byCallee.get("dbms_output.put_line").builtinPackage);
        assertEquals(CallSite.EXTERNAL, byCallee.get("other_pkg.do_it").resolution);
        assertEquals(CallSite.INTERNAL, byCallee.get("b_pkg.run").resolution);
        assertEquals("B_PKG.RUN", byCallee.get("b_pkg.run").targetId);
        assertEquals(CallSite.UNRESOLVED, byCallee.get("missing_proc").resolution);
        assertEquals(CallSite.BUILTIN, byCallee.get("raise_application_error").resolution);

        // collection access, collection methods, package constants and standard SQL functions are not calls
        assertNull(byCallee.get("g_tab"));
        assertNull(byCallee.get("g_tab.count"));
        assertNull(byCallee.get("c_const"));
        assertNull(byCallee.get("a_pkg.c_const"));
        assertNull(byCallee.get("nvl"));
        assertNull(byCallee.get("length"));

        CallSite inLoop = entry.calls.stream().filter(c -> c.callee.equals("helper") && c.inLoop).findFirst().orElse(null);
        assertNotNull(inLoop, "helper(i) inside the FOR loop is flagged inLoop");
    }

    @Test
    void overloadsAreNarrowedByArityAndKeptAmbiguousOtherwise() {
        Routine entry = routine(model(PKG_SPEC, PKG_BODY), "A_PKG.ENTRY");
        List<CallSite> fs = entry.calls.stream().filter(c -> c.callee.equals("f")).toList();
        assertEquals(2, fs.size());
        for (CallSite c : fs) {
            assertNull(c.targetId, "both overloads take one argument: cannot be resolved by arity");
            assertEquals(List.of("A_PKG.F#1", "A_PKG.F#2"), c.candidates);
        }
    }

    @Test
    void graphTableImpactRecursionAndOrphans() {
        StructureModel m = model("""
                create or replace package g as
                  procedure top_p; procedure writer_p; procedure rec_p(n in number);
                end g;
                /
                create or replace package body g as
                  procedure dead is begin null; end;
                  procedure writer_p is begin insert into ledger values (1); end;
                  procedure top_p is begin writer_p; select count(*) into g_n from ledger; end;
                  procedure rec_p(n in number) is begin if n > 0 then rec_p(n - 1); end if; end;
                end g;
                /
                """);
        DependencyGraph g = DependencyGraph.build(m);
        var impact = g.impactOf("LEDGER");
        assertEquals(List.of("G.WRITER_P"), impact.writers());
        assertEquals(List.of("G.TOP_P"), impact.readers());
        assertEquals(List.of("G.TOP_P"), impact.transitiveWriters());
        assertEquals(List.of(List.of("G.REC_P")), g.recursionGroups());
        assertEquals(List.of("G.DEAD"), g.uncalledPrivateRoutines());
        assertTrue(g.reachableFrom("G.TOP_P").contains("G.WRITER_P"));
    }
}
