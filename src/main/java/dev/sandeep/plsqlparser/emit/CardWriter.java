package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Writes routine cards (.md and .json) and unit-block cards (triggers, anonymous blocks, package init blocks). */
final class CardWriter {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final DocContext ctx;
    private final Path out;

    CardWriter(DocContext ctx, Path out) {
        this.ctx = ctx;
        this.out = out;
    }

    // ------------------------------------------------------------------ routine card

    void writeRoutine(Routine r) throws IOException {
        String path = ctx.cardPath.get(r.id);
        Routine spec = ctx.specRoutine(r);
        PlsqlUnit unit = ctx.unitOf.get(r);
        boolean alt = ctx.alternateOnlyIds.contains(r.id);
        String raw = ctx.rawSource(r.file, r.line, r.endLine);
        String purpose = Notes.purpose(r, spec);
        List<String> notes = Notes.oracleNotes(r, raw, ctx.fileHasVariants(r.file));

        StringBuilder b = new StringBuilder();
        b.append("# ").append(r.id).append("\n\n> ").append(Md.code(r.signature)).append("\n\n");
        List<List<String>> meta = new ArrayList<>();
        meta.add(List.of("Kind", r.kind.toLowerCase() + " · " + (r.visibility == null ? "?" : r.visibility)
                + (r.attributes.isEmpty() ? "" : " · " + String.join(", ", r.attributes))));
        if (unit != null && unit.kind.startsWith("PACKAGE") && ctx.pkgPath.containsKey(unit.key()))
            meta.add(List.of("Package", Md.mdLink(unit.name, path, ctx.pkgPath.get(unit.key()))));
        if (r.owner != null && r.scope.equals("LOCAL")) {
            String parentId = r.id.contains(">") ? r.id.substring(0, r.id.lastIndexOf('>')) : null;
            if (parentId != null) meta.add(List.of("Nested in", ctx.routineLink(path, parentId)));
        }
        meta.add(List.of("Source", Md.code(r.file + ":" + r.line + "-" + r.endLine)
                + (spec != null ? " (spec: " + Md.code(spec.file + ":" + spec.line) + ")" : "")
                + (r.linkNote != null ? " — " + r.linkNote : "")));
        if (!r.hasBody) meta.add(List.of("Implementation", r.callSpec != null ? "external call spec: " + Md.code(r.callSpec) : "**declared only — no implementation in the scanned code**"));
        Integer batch = ctx.order.batchOf().get(r.id);
        if (batch != null)
            meta.add(List.of("Conversion order", "batch " + batch + ", rank " + ctx.order.rankOf().get(r.id) + " — see " + Md.mdLink("migration-order", path, "analysis/migration-order.md")));
        if (alt) meta.add(List.of("Variant", "**exists only in the alternate `$IF` variant** (flags assumed TRUE) — see coverage"));
        Metrics mx = r.metrics;
        if (r.hasBody) meta.add(List.of("Complexity", "cyclomatic " + mx.cyclomatic + " · " + mx.linesOfCode + " lines · " + mx.statements
                + " statements · nesting " + mx.maxNesting + " · " + mx.loops + " loops"));
        b.append(Md.table(List.of("", ""), meta)).append('\n');

        b.append("## Purpose\n\n").append(purpose).append("\n\n_Facts:_ ").append(Notes.facts(r)).append("\n\n");

        b.append("## Signature\n\n");
        if (r.params.isEmpty()) b.append("No parameters.");
        else {
            List<List<String>> rows = new ArrayList<>();
            for (Param p : r.params) rows.add(List.of(Md.code(p.name()), p.mode(), Md.code(p.type()), p.defaultValue() == null ? "" : Md.code(p.defaultValue())));
            b.append(Md.table(List.of("Parameter", "Mode", "Type", "Default"), rows));
        }
        if (r.returnType != null) b.append("\n**Returns** ").append(Md.code(r.returnType)).append('\n');
        b.append('\n');

        if (r.hasBody) body(b, path, r.outline, r.sql, r.calls, r.exceptionContract, r.sideEffects, r.id);
        else b.append("## Logic outline\n\n_No body in the scanned code._\n\n");

        localDeclarations(b, path, r);
        notes(b, notes);
        risks(b, r.risks);
        openQuestions(b, r, raw);
        b.append("## Source\n\nOriginal lines ").append(r.line).append('–').append(r.endLine).append(" of ").append(Md.code(r.file))
                .append(" (line numbers on the left are file line numbers):\n\n")
                .append(Md.fence("plsql", ctx.numberedSource(r.file, r.line, r.endLine)));
        write(path, b.toString());
        write(path.replaceFirst("\\.md$", ".json"), json(r, spec, purpose, notes));
    }

    // ------------------------------------------------------------------ unit-block card

    void writeUnitBlock(PlsqlUnit u) throws IOException {
        String path = ctx.unitCardPath.get(u);
        boolean init = u.kind.equals("PACKAGE_BODY");
        String title = init ? u.name + " — package initialisation block" : u.kind.equals("TRIGGER") ? "Trigger " + u.name : u.name;
        StringBuilder b = new StringBuilder("# ").append(title).append("\n\n");
        List<List<String>> meta = new ArrayList<>();
        meta.add(List.of("Kind", init ? "package init block (runs once per session, at first reference)" : u.kind.toLowerCase().replace('_', ' ')));
        meta.add(List.of("Source", Md.code(u.file + ":" + u.line + "-" + u.endLine)));
        if (u.triggerHeader != null) meta.add(List.of("Trigger clause", Md.code(u.triggerHeader)));
        if (init && ctx.pkgPath.containsKey(u.key())) meta.add(List.of("Package", Md.mdLink(u.name, path, ctx.pkgPath.get(u.key()))));
        b.append(Md.table(List.of("", ""), meta)).append('\n');
        if (!u.docComments.isEmpty()) b.append("## Purpose\n\n").append(Notes.cleanComment(u.docComments.get(0))).append("\n\n");
        body(b, path, u.outline, u.sql, u.calls, u.exceptionContract, u.sideEffects, u.name);
        risks(b, u.risks);
        int from = init && u.initBlockLine > 0 ? u.initBlockLine : u.line;
        b.append("## Source\n\n").append(Md.fence("plsql", ctx.numberedSource(u.file, from, u.endLine)));
        write(path, b.toString());
    }

    // ------------------------------------------------------------------ shared sections

    private void body(StringBuilder b, String path, List<LogicStep> outline, List<SqlStatement> sql, List<CallSite> calls,
                      ExceptionContract ec, SideEffects fx, String selfId) {
        b.append("## Logic outline\n\n");
        b.append("Numbered steps nest exactly as in the source; `{…}` shows the tables touched and routines called by that step.\n\n");
        if (outline.isEmpty()) b.append("_Empty body._\n\n");
        else {
            StringBuilder o = new StringBuilder();
            steps(o, path, outline, "", 0);
            b.append(o).append('\n');
        }

        b.append("## SQL statements\n\n");
        if (sql.isEmpty()) b.append("_None._\n\n");
        for (SqlStatement s : sql) sql(b, path, s);

        b.append("## Calls\n\n");
        if (calls.isEmpty()) b.append("_No calls._\n\n");
        else {
            List<List<String>> rows = new ArrayList<>();
            for (CallSite c : calls) rows.add(List.of(String.valueOf(c.line), Md.code(c.callee), c.resolution == null ? "" : c.resolution, target(path, c),
                    (c.inLoop ? "in loop; " : "") + c.argCount + " arg" + (c.argCount == 1 ? "" : "s") + (c.namedArgs.isEmpty() ? "" : ", named " + c.namedArgs)));
            b.append(Md.table(List.of("Line", "Callee", "Resolution", "Target", "Notes"), rows)).append('\n');
        }
        b.append("**Called by:** ");
        Set<String> callers = ctx.calledBy.getOrDefault(selfId, Set.of());
        if (callers.isEmpty()) b.append("_nothing in the scanned code (entry point, external API, or dead code)_");
        else b.append(String.join(", ", callers.stream().map(c -> ctx.routineLink(path, c)).toList()));
        b.append("\n\n");

        b.append("## State & side effects\n\n");
        effects(b, path, fx);
        b.append("## Exception contract\n\n");
        contract(b, ec);
    }

    private void steps(StringBuilder o, String path, List<LogicStep> list, String prefix, int depth) {
        int i = 1;
        for (LogicStep s : list) {
            String num = prefix + i++;
            o.append("  ".repeat(depth)).append("- **").append(num).append("** `").append(s.kind).append('`');
            if (s.label != null) o.append(" `<<").append(s.label).append(">>`");
            if (s.text != null && !s.text.isEmpty()) o.append(' ').append(Md.code(s.text.length() > 160 ? s.text.substring(0, 160) + "…" : s.text));
            o.append(" _(L").append(s.line).append(s.endLine > s.line ? "–" + s.endLine : "").append(")_");
            if (!s.refs.isEmpty()) o.append("  {").append(String.join("; ", s.refs.stream().map(r -> refText(path, r)).toList())).append('}');
            o.append('\n');
            steps(o, path, s.children, num + ".", depth + 1);
        }
    }

    /** Turn "calls X" / "READ T" refs into links where a card/table page exists. */
    private String refText(String path, String ref) {
        if (ref.startsWith("calls ")) {
            String id = ref.substring(6);
            return "calls " + (ctx.cardPath.containsKey(id) ? ctx.routineLink(path, id) : id);
        }
        int sp = ref.indexOf(' ');
        if (sp > 0) {
            String key = ref.substring(sp + 1).replace(" (inferred)", "");
            if (ctx.tablePath.containsKey(key)) return ref.substring(0, sp) + " " + ctx.tableLink(path, key) + (ref.endsWith("(inferred)") ? " (inferred)" : "");
        }
        return ref;
    }

    private void sql(StringBuilder b, String path, SqlStatement s) {
        b.append("### ").append(s.kind).append(" @ line ").append(s.line);
        if (s.endLine > s.line) b.append('–').append(s.endLine);
        b.append("\n\n");
        List<String> flags = new ArrayList<>();
        if (s.inLoop) flags.add("**executed per loop iteration**");
        if (s.bulk) flags.add("bulk");
        if (s.forUpdate) flags.add("FOR UPDATE (locks rows)");
        if (s.dynamic) flags.add("**dynamic SQL — " + s.dynamicConfidence + "**" + (s.dynamicKind == null ? "" : " (" + s.dynamicKind + ")"));
        if (!s.context.equals("BODY")) flags.add("context " + s.context);
        if (!flags.isEmpty()) b.append(String.join(" · ", flags)).append("\n\n");
        for (TableRef t : s.tables) {
            b.append("- ").append(String.join("/", t.access)).append(' ').append(ctx.tableLink(path, t.key()));
            if (t.alias != null) b.append(" (alias ").append(t.alias).append(')');
            if (t.dbLink != null) b.append(" **@").append(t.dbLink).append("**");
            if (!t.readColumns.isEmpty()) b.append(" — reads ").append(Md.code(String.join(", ", t.readColumns)));
            if (!t.writeColumns.isEmpty()) b.append(" — writes ").append(Md.code(String.join(", ", t.writeColumns)));
            if (t.confidence.equals("INFERRED")) b.append(" _(inferred from dynamic SQL)_");
            b.append('\n');
        }
        if (!s.intoTargets.isEmpty()) b.append("- into ").append(Md.code(String.join(", ", s.intoTargets))).append('\n');
        if (!s.binds.isEmpty()) b.append("- PL/SQL values used: ").append(Md.code(String.join(", ", s.binds))).append('\n');
        if (!s.unresolvedColumns.isEmpty()) b.append("- columns not attributable to one table: ").append(Md.code(String.join(", ", s.unresolvedColumns))).append('\n');
        b.append('\n').append(Md.fence("sql", s.text));
        if (s.dynamic && s.dynamicExpression != null) b.append("\nStatement text expression: ").append(Md.code(s.dynamicExpression)).append("\n");
        b.append('\n');
    }

    private String target(String path, CallSite c) {
        if (c.targetId != null) return ctx.routineLink(path, c.targetId);
        if (!c.candidates.isEmpty()) return "one of " + String.join(", ", c.candidates.stream().map(x -> ctx.routineLink(path, x)).toList());
        if (c.builtinPackage != null) return "Oracle " + c.builtinPackage;
        return "";
    }

    private void effects(StringBuilder b, String path, SideEffects fx) {
        List<String> l = new ArrayList<>();
        if (!fx.commits.isEmpty()) l.add("COMMIT at line(s) " + fx.commits);
        if (!fx.rollbacks.isEmpty()) l.add("ROLLBACK at line(s) " + fx.rollbacks);
        if (!fx.savepoints.isEmpty()) l.add("SAVEPOINT at line(s) " + fx.savepoints);
        if (fx.setTransaction) l.add("SET TRANSACTION");
        if (fx.autonomousTransaction) l.add("**autonomous transaction** — commits independently of the caller");
        if (!fx.packageStateWrites.isEmpty()) l.add("writes package state: " + Md.code(String.join(", ", fx.packageStateWrites)));
        if (!fx.packageStateReads.isEmpty()) l.add("reads package state: " + Md.code(String.join(", ", fx.packageStateReads)));
        if (!fx.sequences.isEmpty()) l.add("uses sequences: " + Md.code(String.join(", ", fx.sequences)));
        if (!fx.ddl.isEmpty()) l.add("DDL (implicit commit): " + String.join("; ", fx.ddl));
        if (!fx.dynamicSqlLines.isEmpty()) l.add("dynamic SQL at line(s) " + fx.dynamicSqlLines);
        if (!fx.locks.isEmpty()) l.add("locks: " + String.join("; ", fx.locks));
        if (!fx.dbLinks.isEmpty()) l.add("database links: " + String.join("; ", fx.dbLinks));
        fx.externalIo.forEach((k, v) -> l.add(k + ": " + Md.code(String.join(", ", v))));
        b.append(Md.list(l)).append('\n');
    }

    private void contract(StringBuilder b, ExceptionContract ec) {
        if (ec.raises.isEmpty() && ec.handlers.isEmpty() && ec.implicit.isEmpty()) {
            b.append("_No explicit raises or handlers._\n\n");
            return;
        }
        if (!ec.raises.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (ExceptionContract.Raise r : ec.raises)
                rows.add(List.of(String.valueOf(r.line()), r.how(), r.exception() == null ? "(current exception)" : r.exception(),
                        r.errorCode() != null ? String.valueOf(r.errorCode()) : r.codeExpression() == null ? "" : r.codeExpression()));
            b.append("**Raises**\n\n").append(Md.table(List.of("Line", "How", "Exception", "Code"), rows)).append('\n');
        }
        if (!ec.handlers.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (ExceptionContract.Handler h : ec.handlers)
                rows.add(List.of(h.line() + "–" + h.endLine(), String.join(" OR ", h.exceptions()),
                        h.reraises() ? "re-raises" : h.onlyNull() ? "**swallows (NULL)**" : h.returnsValue() ? "converts to return value" : "**swallows**",
                        (h.callsRoutines() ? "calls/SQL; " : "") + (h.endsTransaction() ? "ends transaction" : "")));
            b.append("**Handlers**\n\n").append(Md.table(List.of("Lines", "When", "Behaviour", "Also"), rows)).append('\n');
        }
        if (!ec.implicit.isEmpty()) b.append("**Implicit** (raised by the engine, not by code here): ").append(Md.code(String.join(", ", ec.implicit))).append("\n\n");
    }

    private void localDeclarations(StringBuilder b, String path, Routine r) {
        Declarations d = r.locals;
        if (d.variables.isEmpty() && d.cursors.isEmpty() && d.types.isEmpty() && d.exceptions.isEmpty() && d.routines.isEmpty()) return;
        b.append("## Local declarations\n\n");
        List<String> l = new ArrayList<>();
        for (Declarations.Variable v : d.variables)
            l.add((v.constant() ? "constant " : "variable ") + Md.code(v.name()) + " " + Md.code(v.type()) + (v.defaultValue() == null ? "" : " := " + Md.code(v.defaultValue())));
        for (Declarations.TypeDecl t : d.types) l.add("type " + Md.code(t.name()) + " (" + t.kind().toLowerCase().replace('_', ' ') + "): " + Md.code(t.definition()));
        for (Declarations.CursorDecl c : d.cursors) l.add("cursor " + Md.code(c.name()) + (c.query() == null ? "" : ": " + Md.code(c.query())));
        for (Declarations.ExceptionDecl e : d.exceptions) l.add("exception " + Md.code(e.name()) + (e.errorCode() == null ? "" : " (error " + e.errorCode() + ")"));
        for (Routine n : d.routines) if (!n.forwardDeclaration) l.add("nested " + n.kind.toLowerCase() + " " + ctx.routineLink(path, n.id));
        b.append(Md.list(l)).append('\n');
    }

    private void notes(StringBuilder b, List<String> notes) {
        b.append("## Oracle semantics notes\n\n");
        b.append(notes.isEmpty() ? "_Nothing Oracle-specific detected beyond the facts above._\n\n" : Md.list(notes) + "\n");
    }

    private void risks(StringBuilder b, List<Risk> risks) {
        b.append("## Risks\n\n");
        if (risks.isEmpty()) b.append("_None detected._\n\n");
        else {
            for (Risk r : risks) b.append("- **").append(r.severity()).append("** `").append(r.code()).append("` ")
                    .append(r.line() > 0 ? "(L" + r.line() + ") " : "").append(r.message()).append('\n');
            b.append('\n');
        }
    }

    private void openQuestions(StringBuilder b, Routine r, String raw) {
        List<String> q = new ArrayList<>();
        for (CallSite c : r.calls) {
            if (c.resolution == null) continue;
            if (c.resolution.equals(CallSite.UNRESOLVED) || c.resolution.equals(CallSite.EXTERNAL) || c.resolution.equals(CallSite.UNRESOLVED_IN_PACKAGE))
                q.add("L" + c.line + ": `" + c.callee + "` is not defined in the scanned code (" + c.resolution + ") — supply its source or contract.");
            else if (!c.candidates.isEmpty())
                q.add("L" + c.line + ": `" + c.callee + "` matches several overloads " + c.candidates + " — decide by argument types.");
        }
        for (SqlStatement s : r.sql)
            if (s.dynamic && !"LITERAL".equals(s.dynamicConfidence))
                q.add("L" + s.line + ": dynamic SQL is " + s.dynamicConfidence + " — the exact statement(s) depend on runtime values.");
            else if (!s.unresolvedColumns.isEmpty())
                q.add("L" + s.line + ": columns " + s.unresolvedColumns + " could not be attributed to a single table.");
        if (ctx.fileHasVariants(r.file)) q.add("Conditional compilation (`$IF`) in this file depends on flags that are not defined; see coverage for the conditions.");
        for (Param p : r.params) if (p.type().toLowerCase().contains("%type") || p.type().toLowerCase().contains("%rowtype"))
            q.add("Parameter `" + p.name() + "` is typed `" + p.type() + "`: its real type lives in the database schema.");
        b.append("## Open questions\n\n").append(q.isEmpty() ? "_None._\n\n" : Md.list(q) + "\n");
    }

    // ------------------------------------------------------------------ JSON

    private String json(Routine r, Routine spec, String purpose, List<String> notes) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("kind", r.kind);
        m.put("name", r.name);
        m.put("package", r.owner);
        m.put("scope", r.scope);
        m.put("visibility", r.visibility);
        m.put("signature", r.signature);
        m.put("attributes", r.attributes);
        m.put("file", r.file);
        m.put("line", r.line);
        m.put("endLine", r.endLine);
        if (spec != null) { m.put("specFile", spec.file); m.put("specLine", spec.line); }
        m.put("hasBody", r.hasBody);
        m.put("alternateVariantOnly", ctx.alternateOnlyIds.contains(r.id));
        m.put("purpose", purpose);
        m.put("params", r.params);
        m.put("returnType", r.returnType);
        m.put("batch", ctx.order.batchOf().get(r.id));
        m.put("metrics", r.metrics);
        m.put("outline", r.outline);
        m.put("sql", r.sql);
        m.put("calls", r.calls);
        m.put("calledBy", ctx.calledBy.getOrDefault(r.id, Set.of()));
        m.put("tablesRead", r.tablesRead);
        m.put("tablesWritten", r.tablesWritten);
        m.put("exceptionContract", r.exceptionContract);
        m.put("sideEffects", r.sideEffects);
        m.put("oracleNotes", notes);
        m.put("risks", r.risks);
        m.put("localDeclarations", localDecls(r.locals));
        m.put("nestedRoutines", r.locals.routines.stream().filter(n -> !n.forwardDeclaration).map(n -> n.id).toList());
        m.put("source", ctx.rawSource(r.file, r.line, r.endLine));
        return JSON.writeValueAsString(m);
    }

    private static Map<String, Object> localDecls(Declarations d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variables", d.variables);
        m.put("types", d.types);
        m.put("cursors", d.cursors);
        m.put("exceptions", d.exceptions);
        m.put("pragmas", d.pragmas);
        return m;
    }

    private void write(String rel, String content) throws IOException {
        Path p = out.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
