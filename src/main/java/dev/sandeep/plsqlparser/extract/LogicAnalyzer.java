package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.extract.StructureExtractor.RoutineCtx;
import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.StructureModel.Issue;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.*;

/** M4 driver: control-flow outline, exception contract, side effects and metrics for every unit block and routine. */
final class LogicAnalyzer {
    private LogicAnalyzer() {}

    static void analyzeUnit(StructureExtractor x, PlsqlUnit u, Registry reg, StructureModel m) {
        Set<String> stateVars = new HashSet<>();
        Registry.PackageInfo pkg = u.kind.startsWith("PACKAGE") ? reg.packages.get(u.key()) : null;
        if (pkg != null) {
            if (pkg.spec() != null) addState(stateVars, pkg.spec());
            if (pkg.body() != null) addState(stateVars, pkg.body());
        }
        List<ParserRuleContext> roots = x.unitBodies.getOrDefault(u, List.of());
        if (!roots.isEmpty()) {
            try {
                OutlineBuilder ob = new OutlineBuilder(u.sql, u.calls);
                u.outline = ob.buildContainers(roots);
                u.exceptionContract = ob.ec;
                u.sideEffects = ob.fx;
                finish(ob, u.sql, u.calls, u.outline, roots, stateVars, Set.of(), m, u.file, u.line);
            } catch (RuntimeException e) {
                u.outline = new ArrayList<>();
                m.issues.add(new Issue("ERROR", "analysis-failed", "logic outline of " + u.name + " could not be built (" + e + "): check the syntax errors in this file", u.file, u.line));
            }
        }
        for (Routine r : u.decls.routines) analyzeRoutine(x, r, stateVars, Set.of(), m);
    }

    static void analyzeRoutine(StructureExtractor x, Routine r, Set<String> stateVars, Set<String> outerShadow, StructureModel m) {
        r.metrics.linesOfCode = r.endLine - r.line + 1;
        RoutineCtx rc = x.routineCtx.get(r);
        Set<String> shadow = new HashSet<>(outerShadow);
        for (Param p : r.params) shadow.add(p.name().toLowerCase());
        r.locals.variables.forEach(v -> shadow.add(v.name().toLowerCase()));
        if (rc != null && rc.body() != null) {
            Src.each(rc.body(), n -> {
                if (n instanceof Cursor_loop_paramContext c) {
                    if (c.index_name() != null) shadow.add(Src.ident(Src.text(c.index_name())));
                    if (c.record_name() != null) shadow.add(Src.ident(Src.text(c.record_name())));
                } else if (n instanceof Forall_statementContext f && f.index_name() != null) shadow.add(Src.ident(Src.text(f.index_name())));
            });
            try {
                OutlineBuilder ob = new OutlineBuilder(r.sql, r.calls);
                r.outline = ob.buildBody(rc.body());
                r.exceptionContract = ob.ec;
                r.sideEffects = ob.fx;
                ob.mx.linesOfCode = r.metrics.linesOfCode;
                r.metrics = ob.mx;
                ob.fx.autonomousTransaction = r.attributes.contains("AUTONOMOUS_TRANSACTION");
                finish(ob, r.sql, r.calls, r.outline, List.of(rc.body()), stateVars, shadow, m, r.file, r.line);
            } catch (RuntimeException e) {
                // a syntax error leaves holes in the parse tree; report it, keep going with the other routines
                r.outline = new ArrayList<>();
                m.issues.add(new Issue("ERROR", "analysis-failed", "logic outline of " + r.id + " could not be built (" + e + "): check the syntax errors in this file", r.file, r.line));
            }
        }
        for (Routine nested : r.locals.routines) analyzeRoutine(x, nested, stateVars, shadow, m);
    }

    private static void finish(OutlineBuilder ob, List<SqlStatement> sql, List<CallSite> calls, List<LogicStep> outline,
                               List<? extends ParserRuleContext> roots, Set<String> stateVars, Set<String> shadow,
                               StructureModel m, String file, int line) {
        SideEffectAnalyzer.analyze(sql, calls, roots, stateVars, shadow, ob.fx);
        for (SqlStatement s : sql) {
            if (!s.intoTargets.isEmpty() && !s.bulk) {
                ob.ec.implicit.add("NO_DATA_FOUND");
                ob.ec.implicit.add("TOO_MANY_ROWS");
            }
        }
        Deque<LogicStep> work = new ArrayDeque<>(outline);
        while (!work.isEmpty()) {
            LogicStep s = work.pop();
            if (s.kind.equals("GRANT")) ob.fx.ddl.add("GRANT (line " + s.line + ")");
            work.addAll(s.children);
        }
        for (String p : ob.problems) m.issues.add(new Issue("WARN", "outline-mismatch", p, file, line));
    }

    private static void addState(Set<String> into, PlsqlUnit u) {
        for (Declarations.Variable v : u.decls.variables) if (!v.constant()) into.add(v.name().toLowerCase());
    }
}
