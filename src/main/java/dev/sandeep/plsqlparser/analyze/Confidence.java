package dev.sandeep.plsqlparser.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sandeep.plsqlparser.analyze.Coverage.FileCoverage;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import dev.sandeep.plsqlparser.model.StructureModel.FileInfo;
import dev.sandeep.plsqlparser.model.StructureModel.SkippedItem;

import java.util.*;

/**
 * How far the analysis of each routine / file can be trusted, and which objects the code uses but the scanned files do not
 * contain. The score is deterministic and explainable: every point lost is a {@link Finding} with a reason.
 *
 * <pre>
 * routine = 100 - unresolved calls (6-8 each, ambiguous overloads 4, capped 40) - dynamic SQL (LITERAL 1, PARTIAL 6, UNKNOWN 12)
 *               - inferred tables (3 each, capped 12) - UNKNOWN outline steps (10 each) - alternate-variant-only (5)
 *               - undecided $IF in file (3) - spec not scanned (3) - syntax errors in file (15)
 * file    = 100 - 25 per syntax error (max 3) - unaccounted code percentage - 2 per undecided $IF (max 10)
 * overall = 0.75 * line-weighted routine average + 0.25 * file average
 * </pre>
 */
public final class Confidence {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Finding(String code, String subject, String file, int line, String message, int penalty) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Score(String id, String file, int line, int weight, int score, String grade, List<Finding> findings) {}

    /** Something the code needs that is not in the scanned files (or was seen but not decomposed). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Missing(String kind, String severity, String name, String detail, List<String> usedBy, String file, int line) {}

    public record Report(int overall, String grade, int routineAverage, int fileAverage, List<Score> routines, List<Score> files,
                         List<Missing> missing, Map<String, Integer> missingByKind) {}

    private static final Set<String> DICTIONARY_PREFIX = Set.of("DUAL", "ALL_", "USER_", "DBA_", "V$", "GV$", "SYS.", "SYSTEM.");

    private Confidence() {}

    public static String grade(int score) {
        return score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : "D";
    }

    public static Report compute(StructureModel m, List<FileCoverage> coverage) {
        Map<String, FileInfo> primary = new HashMap<>();
        for (FileInfo f : m.files) if (f.variant().equals("primary")) primary.put(f.file(), f);
        Set<String> altOnly = new HashSet<>();
        for (Routine r : m.alternateOnlyRoutines) altOnly.add(r.id);

        List<Routine> routines = new ArrayList<>();
        for (PlsqlUnit u : m.units) for (Routine r : u.decls.routines) collect(r, routines);
        for (Routine r : m.alternateOnlyRoutines) collect(r, routines);

        List<Score> routineScores = new ArrayList<>();
        for (Routine r : routines) {
            if (!r.hasBody) continue;
            routineScores.add(scoreRoutine(r, primary.get(r.file), altOnly.contains(r.id)));
        }
        for (PlsqlUnit u : m.units) {
            if (u.sql.isEmpty() && u.calls.isEmpty() && u.outline.isEmpty()) continue;
            String id = u.kind.equals("TRIGGER") ? "TRIGGER:" + u.name.toUpperCase() : u.name.toUpperCase() + ".<" + (u.kind.equals("ANONYMOUS_BLOCK") ? "anonymous" : "init") + ">";
            routineScores.add(score(id, u.file, u.line, Math.max(1, u.endLine - u.line + 1), u.sql, u.calls, u.outline, primary.get(u.file), false, true));
        }
        routineScores.sort(Comparator.comparingInt(Score::score).thenComparing(Score::id));

        List<Score> fileScores = new ArrayList<>();
        Map<String, FileCoverage> cov = new HashMap<>();
        for (FileCoverage c : coverage) cov.put(c.file(), c);
        for (FileInfo f : m.files) {
            if (!f.variant().equals("primary")) continue;
            List<Finding> fs = new ArrayList<>();
            int pen = 0;
            int se = Math.min(f.syntaxErrors(), 3);
            if (f.syntaxErrors() > 0) { fs.add(new Finding("SYNTAX_ERRORS", f.file(), f.file(), 0, f.syntaxErrors() + " syntax error(s): the parser recovered, so nearby logic may be incomplete", 25 * se)); pen += 25 * se; }
            FileCoverage c = cov.get(f.file());
            if (c != null && c.unaccountedCount() > 0) {
                int p = (int) Math.ceil(100 - c.percent());
                fs.add(new Finding("UNACCOUNTED_LINES", f.file(), f.file(), c.unaccounted().get(0)[0], c.unaccountedCount() + " code line(s) are not part of any recognised unit or listed statement", p));
                pen += p;
            }
            if (f.undecidedConditions() > 0) {
                int p = Math.min(10, 2 * f.undecidedConditions());
                fs.add(new Finding("UNDECIDED_IF", f.file(), f.file(), 0, f.undecidedConditions() + " conditional-compilation condition(s) depend on undefined flags; both variants analysed", p));
                pen += p;
            }
            int s = clamp(100 - pen);
            fileScores.add(new Score(f.file(), f.file(), 0, Math.max(1, f.lines()), s, grade(s), fs));
        }
        fileScores.sort(Comparator.comparingInt(Score::score).thenComparing(Score::id));

        int ra = weighted(routineScores), fa = (int) Math.round(fileScores.stream().mapToInt(Score::score).average().orElse(100));
        int overall = routineScores.isEmpty() ? fa : (int) Math.round(0.75 * ra + 0.25 * fa);

        List<Missing> missing = missing(m, routines, altOnly, coverage);
        Map<String, Integer> byKind = new TreeMap<>();
        for (Missing x : missing) byKind.merge(x.kind(), 1, Integer::sum);
        return new Report(overall, grade(overall), ra, fa, routineScores, fileScores, missing, byKind);
    }

    private static void collect(Routine r, List<Routine> out) {
        if (r.id == null || r.forwardDeclaration) return;
        boolean mergedIntoBody = !r.hasBody && r.bodyDefLine != null;
        if (!mergedIntoBody) out.add(r);
        for (Routine n : r.locals.routines) collect(n, out);
    }

    private static int weighted(List<Score> scores) {
        long w = 0, s = 0;
        for (Score x : scores) { w += x.weight(); s += (long) x.weight() * x.score(); }
        return w == 0 ? 100 : (int) Math.round((double) s / w);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ------------------------------------------------------------------ per routine

    private static Score scoreRoutine(Routine r, FileInfo file, boolean altOnly) {
        return score(r.id, r.file, r.line, Math.max(1, r.metrics.linesOfCode), r.sql, r.calls, r.outline, file, altOnly,
                !"UNKNOWN".equals(r.visibility));
    }

    private static Score score(String id, String fileName, int line, int weight, List<SqlStatement> sql, List<CallSite> calls, List<LogicStep> outline,
                               FileInfo file, boolean altOnly, boolean specKnown) {
        List<Finding> fs = new ArrayList<>();
        int callPen = 0;
        for (CallSite c : calls) {
            String res = c.resolution == null ? "" : c.resolution;
            int p = switch (res) {
                case CallSite.UNRESOLVED, CallSite.UNRESOLVED_IN_PACKAGE -> 8;
                case CallSite.EXTERNAL -> 6;
                default -> (res.equals(CallSite.INTERNAL) || res.equals(CallSite.LOCAL)) && !c.candidates.isEmpty() ? 4 : 0;
            };
            if (p == 0) continue;
            callPen += p;
            if (callPen <= 40) fs.add(new Finding(p == 4 ? "AMBIGUOUS_OVERLOAD" : res.equals(CallSite.EXTERNAL) ? "EXTERNAL_CALL" : "UNRESOLVED_CALL", c.callee, fileName, c.line,
                    p == 4 ? c.candidates.size() + " overloads match by argument count; target not pinned" : res.equals(CallSite.EXTERNAL) ? "package is not in the scanned files" : "no routine of that name was found", p));
        }
        int inferred = 0;
        for (SqlStatement s : sql) {
            if (s.dynamic) {
                int p = "UNKNOWN".equals(s.dynamicConfidence) ? 12 : "PARTIAL".equals(s.dynamicConfidence) ? 6 : 1;
                fs.add(new Finding("DYNAMIC_SQL_" + (s.dynamicConfidence == null ? "UNKNOWN" : s.dynamicConfidence), s.dynamicKind == null ? "dynamic SQL" : s.dynamicKind, fileName, s.line,
                        "UNKNOWN".equals(s.dynamicConfidence) ? "statement text is built at run time: tables and columns are unknown"
                                : "PARTIAL".equals(s.dynamicConfidence) ? "tables inferred from string fragments" : "fully literal dynamic SQL, parsed", p));
            }
            for (TableRef t : s.tables) if ("INFERRED".equals(t.confidence) && inferred < 4) {
                inferred++;
                fs.add(new Finding("INFERRED_TABLE", t.key(), fileName, s.line, "table name taken from a dynamic SQL fragment, not a parsed statement", 3));
            }
        }
        for (LogicStep st : flatten(outline))
            if ("UNKNOWN".equals(st.kind)) fs.add(new Finding("UNKNOWN_STEP", st.text == null ? "statement" : st.text, fileName, st.line, "statement kind the outline builder does not model", 10));
        if (altOnly) fs.add(new Finding("ALTERNATE_VARIANT_ONLY", id, fileName, line, "exists only when undefined $$flags are TRUE; confirm which variant is real", 5));
        if (file != null && file.undecidedConditions() > 0) fs.add(new Finding("UNDECIDED_IF", fileName, fileName, 0, "file contains $IF conditions on undefined flags", 3));
        if (!specKnown) fs.add(new Finding("SPEC_NOT_SCANNED", id, fileName, line, "package spec not in the scanned files: visibility is a guess", 3));
        if (file != null && file.syntaxErrors() > 0) fs.add(new Finding("FILE_SYNTAX_ERRORS", fileName, fileName, 0, "the file has syntax errors; the parser may have recovered around this code", 15));
        int s = clamp(100 - fs.stream().mapToInt(Finding::penalty).sum());
        return new Score(id, fileName, line, weight, s, grade(s), fs);
    }

    private static List<LogicStep> flatten(List<LogicStep> steps) {
        List<LogicStep> out = new ArrayList<>();
        Deque<LogicStep> work = new ArrayDeque<>(steps);
        while (!work.isEmpty()) {
            LogicStep s = work.pop();
            out.add(s);
            work.addAll(s.children);
        }
        return out;
    }

    // ------------------------------------------------------------------ missing artifacts

    private static List<Missing> missing(StructureModel m, List<Routine> routines, Set<String> altOnly, List<FileCoverage> coverage) {
        List<Missing> out = new ArrayList<>();

        Map<String, Set<String>> external = new TreeMap<>(), unresolved = new TreeMap<>(), partialDyn = new TreeMap<>();
        Map<String, int[]> where = new HashMap<>();
        Map<String, String> whereFile = new HashMap<>();
        Map<String, Set<String>> tableUsers = new TreeMap<>();
        for (Routine r : routines) visit(r.id, r.file, r.calls, r.sql, external, unresolved, partialDyn, where, whereFile, tableUsers);
        for (PlsqlUnit u : m.units) visit(u.name + (u.kind.equals("TRIGGER") ? ".<trigger>" : ".<init>"), u.file, u.calls, u.sql, external, unresolved, partialDyn, where, whereFile, tableUsers);

        for (var e : external.entrySet())
            out.add(new Missing("EXTERNAL_PACKAGE", "MEDIUM", e.getKey(), "called but not in the scanned files; its behaviour must be supplied or stubbed (" + e.getValue().size() + " caller(s))",
                    new ArrayList<>(e.getValue()), whereFile.get("E" + e.getKey()), where.getOrDefault("E" + e.getKey(), new int[]{0})[0]));
        for (var e : unresolved.entrySet())
            out.add(new Missing("UNRESOLVED_CALL", "HIGH", e.getKey(), "name matched nothing in the folder: a missing routine, a schema-level type constructor, a synonym, or something from another schema",
                    new ArrayList<>(e.getValue()), whereFile.get("U" + e.getKey()), where.getOrDefault("U" + e.getKey(), new int[]{0})[0]));
        for (var e : partialDyn.entrySet())
            out.add(new Missing("DYNAMIC_SQL_OPAQUE", "HIGH", e.getKey(), "SQL text is built at run time; the tables it touches are unknown", List.of(e.getKey().split(" @ ")[0]),
                    whereFile.get("D" + e.getKey()), where.getOrDefault("D" + e.getKey(), new int[]{0})[0]));

        Set<String> defined = new HashSet<>();
        for (TableDef d : m.tableDefs) if (!d.alterOnly) defined.add(d.key());
        for (SkippedItem s : m.skipped) if (s.variant().equals("primary") && s.name() != null && s.kind().toUpperCase().contains("VIEW"))
            defined.add(s.name().toUpperCase().replace("\"", ""));
        for (var e : tableUsers.entrySet()) {
            String t = e.getKey();
            if (isDictionary(t) || defined.contains(t) || defined.stream().anyMatch(d -> d.endsWith("." + t) || t.endsWith("." + d))) continue;
            out.add(new Missing("TABLE_DDL_MISSING", "LOW", t, "referenced by " + e.getValue().size() + " routine(s) but no CREATE TABLE/VIEW in the scanned files: column types and constraints unknown",
                    new ArrayList<>(e.getValue()), null, 0));
        }

        Set<String> inEr = new HashSet<>();   // tables and constraints already read into the ER model are decomposed, not missing
        for (TableDef d : m.tableDefs) inEr.add(d.file + ":" + d.line);
        for (SkippedItem s : m.skipped) if (s.variant().equals("primary") && !inEr.contains(s.file() + ":" + s.line()))
            out.add(new Missing("NOT_DECOMPOSED", s.kind().toUpperCase().contains("TYPE") || s.kind().toUpperCase().contains("VIEW") ? "MEDIUM" : "INFO", s.name() == null ? s.kind() : s.name(),
                    s.kind() + ": " + s.reason(), List.of(), s.file(), s.line()));

        Set<String> specs = new TreeSet<>(), bodies = new TreeSet<>();
        for (PlsqlUnit u : m.units) { if (u.kind.equals("PACKAGE_SPEC")) specs.add(u.key()); if (u.kind.equals("PACKAGE_BODY")) bodies.add(u.key()); }
        for (PlsqlUnit u : m.units) {
            if (u.kind.equals("PACKAGE_SPEC") && !bodies.contains(u.key()))
                out.add(new Missing("BODY_NOT_SCANNED", "HIGH", u.name, "package spec has no body in the scanned files: its public routines have no implementation to analyse", List.of(), u.file, u.line));
            if (u.kind.equals("PACKAGE_BODY") && !specs.contains(u.key()))
                out.add(new Missing("SPEC_NOT_SCANNED", "LOW", u.name, "package body has no spec in the scanned files: public/private visibility is unknown", List.of(), u.file, u.line));
        }
        for (FileInfo f : m.files) {
            if (!f.variant().equals("primary")) continue;
            if (f.syntaxErrors() > 0) out.add(new Missing("SYNTAX_ERRORS", "HIGH", f.file(), f.syntaxErrors() + " syntax error(s)", List.of(), f.file(), 0));
            for (String c : f.undecided()) out.add(new Missing("UNDECIDED_IF", "LOW", c, "conditional-compilation condition depends on an undefined flag; both variants analysed", List.of(), f.file(), 0));
        }
        for (FileCoverage c : coverage) for (int[] r : c.unaccounted())
            out.add(new Missing("UNACCOUNTED_LINES", "HIGH", c.file() + ":" + r[0] + (r[1] > r[0] ? "-" + r[1] : ""), (r[1] - r[0] + 1) + " code line(s) not covered by any unit or listed statement", List.of(), c.file(), r[0]));
        for (String id : altOnly) out.add(new Missing("ALTERNATE_VARIANT_ONLY", "LOW", id, "routine exists only in an alternate $IF variant", List.of(id), null, 0));

        List<String> sev = List.of("HIGH", "MEDIUM", "LOW", "INFO");
        out.sort(Comparator.comparingInt((Missing x) -> sev.indexOf(x.severity())).thenComparing(Missing::kind).thenComparing(Missing::name));
        return out;
    }

    private static void visit(String id, String file, List<CallSite> calls, List<SqlStatement> sql, Map<String, Set<String>> external, Map<String, Set<String>> unresolved,
                              Map<String, Set<String>> dyn, Map<String, int[]> where, Map<String, String> whereFile, Map<String, Set<String>> tableUsers) {
        for (CallSite c : calls) {
            String res = c.resolution == null ? "" : c.resolution;
            if (res.equals(CallSite.EXTERNAL)) {
                String pkg = c.callee.contains(".") ? c.callee.substring(0, c.callee.indexOf('.')).toUpperCase() : c.callee.toUpperCase();
                external.computeIfAbsent(pkg, k -> new TreeSet<>()).add(id);
                where.putIfAbsent("E" + pkg, new int[]{c.line});
                whereFile.putIfAbsent("E" + pkg, file);
            } else if (res.equals(CallSite.UNRESOLVED) || res.equals(CallSite.UNRESOLVED_IN_PACKAGE)) {
                String n = c.callee.toUpperCase();
                unresolved.computeIfAbsent(n, k -> new TreeSet<>()).add(id);
                where.putIfAbsent("U" + n, new int[]{c.line});
                whereFile.putIfAbsent("U" + n, file);
            }
        }
        for (SqlStatement s : sql) {
            if (s.dynamic && "UNKNOWN".equals(s.dynamicConfidence)) {
                String k = id + " @ " + s.line;
                dyn.computeIfAbsent(k, x -> new TreeSet<>()).add(id);
                where.putIfAbsent("D" + k, new int[]{s.line});
                whereFile.putIfAbsent("D" + k, file);
            }
            for (TableRef t : s.tables) tableUsers.computeIfAbsent(t.key(), k -> new TreeSet<>()).add(id);
        }
    }

    private static boolean isDictionary(String t) {
        String u = t.toUpperCase();
        return DICTIONARY_PREFIX.stream().anyMatch(u::startsWith);
    }
}
