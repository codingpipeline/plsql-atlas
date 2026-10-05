package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.parse.Discover;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StructureTest {
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

    private static final String SPEC = """
            create or replace package pkg authid current_user as
              c_max constant number := 10;
              e_bad exception;
              pragma exception_init(e_bad, -20001);
              type t_rec is record (id number, name varchar2(30));
              type t_tab is table of t_rec index by pls_integer;
              cursor c_all(p_id number) return t_rec;
              -- adds one
              function add_one(p_x in number) return number deterministic;
              procedure log(p_msg in varchar2);
              procedure log(p_msg in varchar2, p_level in number default 1);
            end pkg;
            /
            """;
    private static final String BODY = """
            create or replace package body pkg as
              g_state number := 0;
              procedure helper;
              function add_one(p_x in number) return number deterministic is
                pragma autonomous_transaction;
                procedure inner_p is begin null; end;
              begin
                /* header text */
                inner_p;
                return p_x + 1;
              end;
              procedure log(p_msg in varchar2) is begin null; end;
              procedure log(p_msg in varchar2, p_level in number default 1) is begin null; end;
              procedure helper is begin null; end;
            begin
              g_state := 1;
            end pkg;
            /
            """;

    @Test
    void linksSpecToBodyAndMarksVisibility() {
        StructureModel m = model(SPEC, BODY);
        PlsqlUnit body = m.units.stream().filter(u -> u.kind.equals("PACKAGE_BODY")).findFirst().orElseThrow();
        Map<String, Routine> byId = new HashMap<>();
        for (Routine r : body.decls.routines) if (!r.forwardDeclaration) byId.put(r.id, r);
        assertEquals("PUBLIC", byId.get("PKG.ADD_ONE").visibility);
        assertEquals("PRIVATE", byId.get("PKG.HELPER").visibility);
        assertEquals("PUBLIC", byId.get("PKG.LOG#1").visibility);
        assertEquals("PUBLIC", byId.get("PKG.LOG#2").visibility);
        assertTrue(body.hasInitBlock);
        assertTrue(m.issues.stream().noneMatch(i -> i.severity().equals("ERROR")), m.issues::toString);
    }

    @Test
    void extractsSpecDeclarations() {
        PlsqlUnit spec = model(SPEC).units.get(0);
        assertEquals("authid current_user", spec.authid.toLowerCase());
        assertEquals(1, spec.decls.variables.size());
        assertTrue(spec.decls.variables.get(0).constant());
        assertEquals(-20001, spec.decls.exceptions.get(0).errorCode());
        assertEquals(List.of("RECORD", "TABLE"), spec.decls.types.stream().map(Declarations.TypeDecl::kind).toList());
        assertEquals(2, spec.decls.types.get(0).fields().size());
        assertEquals(1, spec.decls.cursors.get(0).params().size());
        Routine add = spec.decls.routines.get(0);
        assertTrue(add.attributes.contains("DETERMINISTIC"));
        assertEquals("number", add.returnType);
        assertEquals(List.of("adds one"), add.docComments);
        assertFalse(add.hasBody);
    }

    @Test
    void extractsBodyDetails() {
        PlsqlUnit body = model(SPEC, BODY).units.stream().filter(u -> u.kind.equals("PACKAGE_BODY")).findFirst().orElseThrow();
        Routine fwd = body.decls.routines.get(0);
        assertTrue(fwd.forwardDeclaration);
        Routine add = body.decls.routines.stream().filter(r -> r.name.equals("add_one")).findFirst().orElseThrow();
        assertTrue(add.attributes.contains("AUTONOMOUS_TRANSACTION"));
        assertEquals(List.of("header text"), add.bodyHeaderComments);
        assertEquals(1, add.locals.routines.size());
        assertEquals("PKG.ADD_ONE>INNER_P", add.locals.routines.get(0).id);
        assertEquals("LOCAL", add.locals.routines.get(0).visibility);
        Routine log2 = body.decls.routines.stream().filter(r -> r.id.equals("PKG.LOG#2")).findFirst().orElseThrow();
        assertEquals("1", log2.params.get(1).defaultValue());
        assertEquals("IN", log2.params.get(1).mode());
    }

    @Test
    void reportsSpecRoutineWithoutBody() {
        StructureModel m = model(SPEC, "create or replace package body pkg as procedure helper is begin null; end; end pkg;\n/\n");
        assertTrue(m.issues.stream().anyMatch(i -> i.code().equals("spec-routine-without-body")));
    }

    @Test
    void standaloneRoutinesAndTriggersAndSkippedDdl() {
        StructureModel m = model("""
                create or replace function f(p number) return number is begin return p; end;
                /
                create or replace procedure p(x in out nocopy varchar2) is begin null; end;
                /
                create table t (id number)
                /
                
                create or replace trigger trg before insert on t for each row begin null; end;
                /
                """);
        assertEquals(List.of("FUNCTION", "PROCEDURE", "TRIGGER"), m.units.stream().map(u -> u.kind).toList());
        assertEquals("STANDALONE", m.units.get(0).decls.routines.get(0).visibility);
        assertEquals("IN OUT NOCOPY", m.units.get(1).decls.routines.get(0).params.get(0).mode());
        assertTrue(m.units.get(2).triggerHeader.toLowerCase().contains("before insert on t"));
        assertTrue(m.skipped.stream().anyMatch(s -> s.kind().equals("create_table")), m.skipped::toString);
    }

    @Test
    void realWorldLoggerLinksEveryPublicRoutine() throws Exception {
        Path dir = Path.of("corpus", "real");
        assumeTrue(java.nio.file.Files.isDirectory(dir));
        StructureModel m = StructureBuilder.build(PlSqlParserFacade.parseFiles(Discover.files(dir), Map.of(), new int[]{19, 0}));
        assertTrue(m.issues.stream().noneMatch(i -> i.severity().equals("ERROR")), m.issues::toString);
        PlsqlUnit spec = m.units.stream().filter(u -> u.kind.equals("PACKAGE_SPEC") && u.name.equals("logger")).findFirst().orElseThrow();
        assertTrue(spec.decls.routines.size() > 40);
        assertTrue(spec.decls.routines.stream().allMatch(r -> r.bodyDefLine != null), "every spec routine linked to a body");
    }
}
