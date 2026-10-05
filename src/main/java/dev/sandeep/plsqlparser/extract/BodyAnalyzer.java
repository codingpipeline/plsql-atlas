package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.extract.StructureExtractor.RoutineCtx;
import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.*;

/** M3 driver: runs SQL and call extraction over every unit-level block and routine body, with proper variable scoping. */
final class BodyAnalyzer {
    private BodyAnalyzer() {}

    static void analyzeUnit(StructureExtractor x, PlsqlUnit u, Registry reg) {
        Set<String> vars = new HashSet<>();
        addDecls(vars, u.decls);
        Registry.PackageInfo pkg = u.kind.startsWith("PACKAGE") ? reg.packages.get(u.key()) : null;
        if (pkg != null) {
            if (pkg.spec() != null) addDecls(vars, pkg.spec().decls);
            if (pkg.body() != null) addDecls(vars, pkg.body().decls);
        }
        List<ParserRuleContext> roots = new ArrayList<>(x.unitBodies.getOrDefault(u, List.of()));
        roots.addAll(x.cursorCtx.getOrDefault(u.decls, List.of()));
        roots.addAll(x.initCtx.getOrDefault(u.decls, List.of()));
        String where = u.kind.equals("TRIGGER") ? "TRIGGER" : u.kind.equals("ANONYMOUS_BLOCK") ? "ANONYMOUS_BLOCK" : "PACKAGE_LEVEL";
        run(roots, vars, where, reg, u.sql, u.calls);
        for (Routine r : u.decls.routines) analyzeRoutine(x, r, vars, reg);
    }

    static void analyzeRoutine(StructureExtractor x, Routine r, Set<String> outerVars, Registry reg) {
        Set<String> vars = new HashSet<>(outerVars);
        for (Param p : r.params) vars.add(p.name().toLowerCase());
        addDecls(vars, r.locals);
        RoutineCtx rc = x.routineCtx.get(r);
        List<ParserRuleContext> roots = new ArrayList<>();
        if (rc != null && rc.body() != null) {
            roots.add(rc.body());
            Src.each(rc.body(), n -> { // implicit loop variables: FOR i IN ..., FOR rec IN ..., FORALL i IN ...
                if (n instanceof Cursor_loop_paramContext c) {
                    if (c.index_name() != null) vars.add(Src.ident(Src.text(c.index_name())));
                    if (c.record_name() != null) vars.add(Src.ident(Src.text(c.record_name())));
                } else if (n instanceof Forall_statementContext f && f.index_name() != null) vars.add(Src.ident(Src.text(f.index_name())));
            });
        }
        roots.addAll(x.cursorCtx.getOrDefault(r.locals, List.of()));
        roots.addAll(x.initCtx.getOrDefault(r.locals, List.of()));
        roots.addAll(x.paramDefaultCtx.getOrDefault(r, List.of()));
        run(roots, vars, "BODY", reg, r.sql, r.calls);
        summarize(r);
        for (Routine nested : r.locals.routines) analyzeRoutine(x, nested, vars, reg);
    }

    private static void run(List<ParserRuleContext> roots, Set<String> vars, String where, Registry reg,
                            List<SqlStatement> sqlOut, List<CallSite> callsOut) {
        SqlExtractor se = new SqlExtractor(vars, where);
        CallExtractor ce = new CallExtractor(vars, reg);
        for (ParserRuleContext root : roots) {
            se.scan(root);
            ce.scan(root);
        }
        sqlOut.addAll(se.out);
        callsOut.addAll(ce.out);
    }

    private static void addDecls(Set<String> vars, Declarations d) {
        d.variables.forEach(v -> vars.add(v.name().toLowerCase()));
        d.cursors.forEach(c -> vars.add(c.name().toLowerCase()));
        d.exceptions.forEach(e -> vars.add(e.name().toLowerCase()));
    }

    static void summarize(Routine r) {
        for (SqlStatement s : r.sql) {
            for (TableRef t : s.tables) {
                for (String a : t.access) {
                    if (a.equals("READ") || a.equals("LOCK")) r.tablesRead.add(t.key());
                    else r.tablesWritten.add(t.key());
                }
            }
        }
    }
}
