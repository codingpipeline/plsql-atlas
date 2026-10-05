package dev.sandeep.plsqlparser.analyze;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.ExceptionContract.Handler;
import dev.sandeep.plsqlparser.model.ExceptionContract.Raise;

import java.util.*;
import java.util.regex.Pattern;

/**
 * M4: Oracle-specific hazards an engineer (or coding agent) must not lose when reading or porting PL/SQL.
 * Rules are deterministic and only look at the already-extracted model.
 */
public final class RiskRules {
    private static final Pattern BULK_FETCH = Pattern.compile("(?i)bulk\\s+collect");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\blimit\\b");
    private static final Map<String, Integer> ORDER = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2, "INFO", 3);

    private RiskRules() {}

    public static void apply(StructureModel m) {
        for (PlsqlUnit u : m.units) {
            if (!u.outline.isEmpty() || !u.sql.isEmpty() || !u.calls.isEmpty())
                rules(u.name + (u.kind.equals("TRIGGER") ? " (trigger)" : " (block)"), u.file, u.sql, u.calls, u.exceptionContract,
                        u.sideEffects, u.outline, null, Set.of(), u.risks, u.line);
            for (Routine r : u.decls.routines) routine(r, m);
            m.risks.addAll(u.risks);
        }
        for (Routine r : m.alternateOnlyRoutines) routine(r, m);
        m.risks.sort(Comparator.comparing((Risk r) -> ORDER.getOrDefault(r.severity(), 9)).thenComparing(Risk::code)
                .thenComparing(Risk::routineId, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingInt(Risk::line));
    }

    private static void routine(Routine r, StructureModel m) {
        rules(r.id == null ? r.name : r.id, r.file, r.sql, r.calls, r.exceptionContract, r.sideEffects, r.outline, r.metrics, r.attributes, r.risks, r.line);
        m.risks.addAll(r.risks);
        for (Routine n : r.locals.routines) routine(n, m);
    }

    private static void rules(String id, String file, List<SqlStatement> sql, List<CallSite> calls, ExceptionContract ec, SideEffects fx,
                              List<LogicStep> outline, Metrics mx, Set<String> attributes, List<Risk> out, int startLine) {
        List<LogicStep> steps = new ArrayList<>();
        flatten(outline, steps);
        int before = out.size();

        // ---- exceptions
        for (Handler h : ec.handlers) {
            if (!h.others() || !h.swallows()) continue;
            if (h.onlyNull())
                add(out, "WHEN_OTHERS_NULL", "HIGH", "WHEN OTHERS THEN NULL silently discards every error", id, file, h.line());
            else if (h.returnsValue() && !h.callsRoutines())
                add(out, "WHEN_OTHERS_RETURNS_DEFAULT", "MEDIUM", "WHEN OTHERS converts every error into a return value: callers cannot tell failure from a genuine result", id, file, h.line());
            else if (!h.callsRoutines())
                add(out, "WHEN_OTHERS_SWALLOWED", "HIGH", "WHEN OTHERS handler does not re-raise and does nothing else visible: errors vanish", id, file, h.line());
            else
                add(out, "WHEN_OTHERS_NO_RERAISE", "MEDIUM", "WHEN OTHERS handler logs/calls but never re-raises: the caller never sees the failure", id, file, h.line());
        }
        for (Raise r : ec.raises)
            if (r.errorCode() != null && (r.errorCode() > -20000 || r.errorCode() < -20999))
                add(out, "APP_ERROR_CODE_OUT_OF_RANGE", "MEDIUM", "RAISE_APPLICATION_ERROR(" + r.errorCode() + ") is outside the allowed -20000..-20999 range", id, file, r.line());
        if (ec.implicit.contains("NO_DATA_FOUND") && !ec.handlesOthers() && !ec.handles("NO_DATA_FOUND"))
            add(out, "SELECT_INTO_NO_HANDLER", "LOW", "SELECT ... INTO can raise NO_DATA_FOUND/TOO_MANY_ROWS and nothing here handles it: it propagates to the caller", id, file, firstLine(sql, s -> !s.intoTargets.isEmpty()));

        // ---- transactions and state
        if (fx.endsTransaction() && !fx.autonomousTransaction)
            add(out, "COMMIT_IN_ROUTINE", "MEDIUM", "COMMIT/ROLLBACK inside a routine: transaction boundaries are decided here, not by the caller", id, file,
                    !fx.commits.isEmpty() ? fx.commits.get(0) : fx.rollbacks.get(0));
        if (fx.autonomousTransaction)
            add(out, "AUTONOMOUS_TRANSACTION", "MEDIUM", "runs in an autonomous transaction: commits independently of the caller (needs an explicit design when ported)", id, file, 0);
        if (!fx.packageStateWrites.isEmpty())
            add(out, "PACKAGE_STATE_WRITE", "INFO", "writes package-level state " + fx.packageStateWrites + " (session-scoped, survives between calls)", id, file, 0);
        for (String d : fx.ddl)
            add(out, "DDL_IN_PLSQL", "MEDIUM", "DDL from PL/SQL (" + d + ") commits implicitly and needs elevated privileges", id, file, 0);
        for (String l : fx.locks)
            add(out, "ROW_LOCKING", "INFO", "explicit locking: " + l, id, file, 0);
        for (String link : fx.dbLinks)
            add(out, "DB_LINK", "INFO", "uses database link " + link, id, file, 0);
        fx.externalIo.forEach((cat, routines) -> {
            if (cat.equals("CONSOLE") || cat.equals("OTHER_BUILTIN") || cat.equals("LOB") || cat.equals("XML") || cat.equals("CRYPTO_RANDOM") || cat.equals("ADMIN")) return;
            String sev = cat.equals("NETWORK") || cat.equals("FILE_IO") || cat.equals("SCHEDULER") ? "MEDIUM" : "INFO";
            add(out, "EXTERNAL_SIDE_EFFECT", sev, cat + " via " + routines, id, file, 0);
        });

        // ---- SQL shape
        for (SqlStatement s : sql) {
            boolean dml = Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(s.kind);
            if (s.inLoop && dml && !s.bulk)
                add(out, "ROW_BY_ROW_DML", "MEDIUM", s.kind + " executed once per loop iteration (consider set-based SQL or FORALL)", id, file, s.line);
            else if (s.inLoop && s.kind.equals("SELECT"))
                add(out, "SQL_IN_LOOP", "LOW", "SELECT executed once per loop iteration (N+1 pattern)", id, file, s.line);
            if (s.dynamic && !"LITERAL".equals(s.dynamicConfidence) && s.dynamicExpression != null && s.dynamicExpression.contains("||")) {
                boolean asserted = s.dynamicExpression.toLowerCase().contains("dbms_assert");
                add(out, "DYNAMIC_SQL_CONCATENATION", asserted ? "LOW" : "HIGH",
                        "dynamic SQL built by concatenation" + (asserted ? " (DBMS_ASSERT used)" : " without DBMS_ASSERT: SQL-injection risk") +
                                (s.binds.isEmpty() ? "; no bind variables" : ""), id, file, s.line);
            } else if (s.dynamic && "UNKNOWN".equals(s.dynamicConfidence)) {
                add(out, "DYNAMIC_SQL_OPAQUE", "MEDIUM", "dynamic SQL statement text comes from a variable: tables touched cannot be determined statically", id, file, s.line);
            }
            if (s.bulk && s.kind.equals("SELECT") && !s.intoTargets.isEmpty())
                add(out, "UNBOUNDED_BULK_COLLECT", "MEDIUM", "SELECT ... BULK COLLECT INTO has no LIMIT: memory grows with the row count", id, file, s.line);
            if (s.text != null && s.text.contains("/*+"))
                add(out, "OPTIMIZER_HINT", "INFO", "SQL contains an optimizer hint", id, file, s.line);
        }
        for (LogicStep st : steps) {
            if (st.kind.equals("FETCH") && st.text != null && BULK_FETCH.matcher(st.text).find() && !LIMIT.matcher(st.text).find())
                add(out, "UNBOUNDED_BULK_COLLECT", "MEDIUM", "FETCH ... BULK COLLECT without LIMIT", id, file, st.line);
            if (st.kind.equals("GOTO"))
                add(out, "GOTO_USED", "LOW", "GOTO makes control flow harder to port: " + st.text, id, file, st.line);
        }

        // ---- calls
        for (CallSite c : calls) {
            if (CallSite.UNRESOLVED.equals(c.resolution) || CallSite.EXTERNAL.equals(c.resolution) || CallSite.UNRESOLVED_IN_PACKAGE.equals(c.resolution))
                add(out, "UNRESOLVED_CALL", "INFO", "call to " + c.callee + " could not be resolved in the scanned code (" + c.resolution + ")", id, file, c.line);
            else if (!c.candidates.isEmpty())
                add(out, "AMBIGUOUS_OVERLOAD", "INFO", "call to " + c.callee + " matches several overloads " + c.candidates + " (argument types decide)", id, file, c.line);
        }

        // ---- size / complexity
        if (mx != null) {
            if (mx.cyclomatic > 50) add(out, "HIGH_COMPLEXITY", "HIGH", "cyclomatic complexity " + mx.cyclomatic, id, file, 0);
            else if (mx.cyclomatic > 20) add(out, "HIGH_COMPLEXITY", "MEDIUM", "cyclomatic complexity " + mx.cyclomatic, id, file, 0);
            if (mx.linesOfCode > 300) add(out, "LONG_ROUTINE", "LOW", mx.linesOfCode + " lines", id, file, 0);
            if (mx.maxNesting >= 6) add(out, "DEEP_NESTING", "LOW", "nesting depth " + mx.maxNesting, id, file, 0);
        }
        for (int i = before; i < out.size(); i++) { // routine-wide findings point at the routine's first line
            Risk k = out.get(i);
            if (k.line() == 0) out.set(i, new Risk(k.code(), k.severity(), k.message(), k.routineId(), k.file(), startLine));
        }
    }

    private static int firstLine(List<SqlStatement> sql, java.util.function.Predicate<SqlStatement> p) {
        for (SqlStatement s : sql) if (p.test(s)) return s.line;
        return 0;
    }

    private static void flatten(List<LogicStep> in, List<LogicStep> out) {
        for (LogicStep s : in) {
            out.add(s);
            flatten(s.children, out);
        }
    }

    private static void add(List<Risk> out, String code, String severity, String message, String id, String file, int line) {
        out.add(new Risk(code, severity, message, id, file, line));
    }
}
