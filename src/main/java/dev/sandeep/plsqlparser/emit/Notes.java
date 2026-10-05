package dev.sandeep.plsqlparser.emit;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.parse.Preprocessor;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic text derived from the model: purpose summaries and "Oracle semantics" notes for a routine. */
final class Notes {
    private Notes() {}

    private record Rule(Pattern pattern, String note) {}

    private static Rule rule(String regex, String note) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), note);
    }

    /** Matched against the source with comments and string contents blanked. */
    private static final List<Rule> RULES = List.of(
            rule("\\bnvl2?\\s*\\(|\\bdecode\\s*\\(|\\bis\\s+(not\\s+)?null\\b|\\bcoalesce\\s*\\(",
                    "NULL semantics: Oracle `NVL`/`DECODE`/`COALESCE` and `IS NULL` rules differ from most languages (e.g. `DECODE(a, b, ...)` treats two NULLs as equal, NULL compares unknown)."),
            rule("\\brownum\\b", "`ROWNUM` is assigned before `ORDER BY`; a port must reproduce the filter-then-sort order."),
            rule("\\browid\\b", "`ROWID` is an Oracle physical row address; there is no portable equivalent."),
            rule("\\bsysdate\\b|\\bsystimestamp\\b|\\bcurrent_(date|timestamp)\\b",
                    "Date/time values come from the database server clock/time zone; `DATE` in Oracle carries a time part and `date - n` is arithmetic in days."),
            rule("\\bto_(char|date|number|timestamp)\\s*\\(", "Conversions and format masks can depend on NLS session settings (date format, decimal separator)."),
            rule("\\bsys_context\\s*\\(|\\buserenv\\s*\\(", "Reads session/context information (`SYS_CONTEXT`/`USERENV`) that must be supplied explicitly outside Oracle."),
            rule("%rowtype|%type\\b", "Types are anchored to table/column definitions (`%TYPE`/`%ROWTYPE`): resolve the real column types from the schema."),
            rule("\\bindex\\s+by\\b", "Associative arrays (`INDEX BY`) are sparse and key-ordered; iteration uses `FIRST`/`NEXT`, not 0..n-1."),
            rule("\\bvarray\\b|\\.extend\\b|\\bnested\\s+table\\b|\\btable\\s+of\\b",
                    "Collections are 1-based; nested tables/VARRAYs must be `EXTEND`ed before assignment and can become sparse after `DELETE`."),
            rule("\\bbulk\\s+collect\\b|\\bforall\\b",
                    "Bulk operations: `FORALL` is a single context switch, not a loop; check `SAVE EXCEPTIONS`, `SQL%BULK_ROWCOUNT` and `SQL%BULK_EXCEPTIONS` handling."),
            rule("%found\\b|%notfound\\b|%rowcount\\b|%isopen\\b", "Cursor attributes (`%FOUND`, `%NOTFOUND`, `%ROWCOUNT`, `%ISOPEN`) have Oracle-specific timing: they describe the most recent fetch/DML."),
            rule("\\bsqlcode\\b|\\bsqlerrm\\b", "`SQLCODE`/`SQLERRM` expose Oracle error numbers/messages; callers may depend on them."),
            rule(":new\\b|:old\\b", "Row-level trigger pseudo-records `:NEW`/`:OLD` carry before/after images of the row."),
            rule("\\bfor\\s+update\\b", "`SELECT ... FOR UPDATE` takes row locks held until the transaction ends (see `NOWAIT`/`SKIP LOCKED`/`WAIT`)."),
            rule("\\bconnect\\s+by\\b|\\bstart\\s+with\\b", "Hierarchical query (`CONNECT BY`): `LEVEL`, `PRIOR` and `NOCYCLE` semantics are Oracle-specific."),
            rule("\\bmerge\\s+into\\b", "`MERGE` combines insert and update in one atomic statement; row-by-row ports can change locking and result counts."),
            rule("\\bpipe\\s+row\\b", "Pipelined table function: rows are streamed to the caller as produced, not returned at the end."),
            rule("\\braise_application_error\\b", "`RAISE_APPLICATION_ERROR` raises user errors in -20000..-20999 that callers may match by number."),
            rule("\\bdbms_output\\b", "`DBMS_OUTPUT` writes to a buffer shown by the client, not to a log: the port needs a deliberate logging choice."));

    static List<String> oracleNotes(Routine r, String rawSource, boolean variantsExist) {
        String masked = Preprocessor.mask(rawSource);
        List<String> out = new ArrayList<>();
        // empty string = NULL needs the original text (string literals are blanked in the masked copy)
        if (rawSource.contains("''") && !rawSource.contains("''''") || Pattern.compile("(?i)\\|\\||\\bvarchar2\\b").matcher(masked).find() && rawSource.contains("''"))
            out.add("Empty string is NULL: in Oracle `''` and NULL are the same value, so `x = ''` is never true and `LENGTH('')` is NULL.");
        for (Rule rl : RULES) if (rl.pattern().matcher(masked).find()) out.add(rl.note());
        if (r.attributes.contains("PIPELINED")) out.add("Declared `PIPELINED`: callable as `TABLE(function(...))` in SQL.");
        if (r.attributes.contains("DETERMINISTIC")) out.add("Declared `DETERMINISTIC`: Oracle may cache results for identical arguments (also used for function-based indexes).");
        if (r.attributes.contains("RESULT_CACHE") || r.attributes.stream().anyMatch(a -> a.startsWith("RESULT_CACHE")))
            out.add("`RESULT_CACHE`: results are cached cross-session and invalidated when the data it depends on changes.");
        if (r.attributes.contains("AUTONOMOUS_TRANSACTION"))
            out.add("`AUTONOMOUS_TRANSACTION`: runs and commits in its own transaction, invisible to the caller's uncommitted changes.");
        if (r.attributes.stream().anyMatch(a -> a.contains("CURRENT_USER")))
            out.add("`AUTHID CURRENT_USER` (invoker's rights): object names resolve against the caller's privileges and schema.");
        if (r.params.stream().anyMatch(p -> p.mode().contains("NOCOPY"))) out.add("`NOCOPY` parameters are passed by reference: the caller sees modifications even if the routine fails.");
        if (r.params.stream().anyMatch(p -> p.mode().startsWith("OUT") || p.mode().startsWith("IN OUT")))
            out.add("`OUT`/`IN OUT` parameters: results come back through arguments; on an unhandled exception their values are undefined to the caller.");
        if (variantsExist) out.add("The file uses conditional compilation (`$IF`): behaviour differs between the primary and alternate variants (see coverage).");
        return out;
    }

    // ------------------------------------------------------------------ purpose

    /** Plain-language summary: first paragraph of the doc comment, else a phrase made from the name. */
    static String purpose(Routine r, Routine spec) {
        List<String> docs = new ArrayList<>();
        if (spec != null) docs.addAll(spec.docComments);
        docs.addAll(r.docComments);
        docs.addAll(r.bodyHeaderComments);
        for (String d : docs) {
            String c = cleanComment(d);
            if (!c.isEmpty()) return c;
        }
        String phrase = r.name.replace('_', ' ').trim();
        phrase = phrase.isEmpty() ? r.name : Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1);
        return phrase + (r.returnType != null ? " (function returning " + r.returnType + ")" : " (procedure)") + " — no documentation comment in source";
    }

    static String cleanComment(String raw) {
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String t = line.strip().replaceFirst("^[*\\-=]+\\s?", "").strip();
            if (t.isEmpty()) {
                if (b.length() > 0) break; // first paragraph only
                continue;
            }
            if (t.matches("(?i)^(related tickets|notes|who\\s+date\\s+description|remarks).*") && b.length() > 0) break;
            if (t.matches("^[-=_ ]{4,}$")) continue;
            b.append(b.length() > 0 ? " " : "").append(t);
        }
        String s = b.toString().replaceAll("(?i)^purpose:\\s*", "").strip();
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** One-sentence factual summary built from the extracted model. */
    static String facts(Routine r) {
        List<String> f = new ArrayList<>();
        f.add(r.kind.toLowerCase() + (r.visibility != null ? ", " + r.visibility.toLowerCase() : ""));
        if (!r.tablesRead.isEmpty()) f.add("reads " + String.join(", ", r.tablesRead));
        if (!r.tablesWritten.isEmpty()) f.add("writes " + String.join(", ", r.tablesWritten));
        long ext = r.calls.stream().filter(c -> c.resolution != null && !c.resolution.equals(CallSite.INTERNAL) && !c.resolution.equals(CallSite.LOCAL)).count();
        if (!r.calls.isEmpty()) f.add(r.calls.size() + " call" + (r.calls.size() == 1 ? "" : "s") + (ext > 0 ? " (" + ext + " built-in/external/unresolved)" : ""));
        if (r.sideEffects.endsTransaction()) f.add("ends the transaction");
        if (r.sideEffects.autonomousTransaction) f.add("autonomous transaction");
        if (!r.exceptionContract.raises.isEmpty()) f.add("raises errors");
        if (!r.sideEffects.packageStateWrites.isEmpty()) f.add("modifies package state");
        f.add("cyclomatic " + r.metrics.cyclomatic + ", " + r.metrics.linesOfCode + " lines");
        return String.join("; ", f);
    }
}
