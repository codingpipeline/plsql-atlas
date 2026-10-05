package dev.sandeep.plsqlparser.emit;

import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Writes {@code objects/<pkg>/_package.md} and {@code tables/<table>.md}. */
final class PackageAndTableWriter {
    private final DocContext ctx;
    private final Path out;

    PackageAndTableWriter(DocContext ctx, Path out) {
        this.ctx = ctx;
        this.out = out;
    }

    // ------------------------------------------------------------------ packages

    void writePackages() throws IOException {
        Map<String, PlsqlUnit> specs = new TreeMap<>(), bodies = new TreeMap<>();
        for (PlsqlUnit u : ctx.model.units) {
            if (u.kind.equals("PACKAGE_SPEC")) specs.putIfAbsent(u.key(), u);
            else if (u.kind.equals("PACKAGE_BODY")) bodies.putIfAbsent(u.key(), u);
        }
        Set<String> keys = new TreeSet<>(specs.keySet());
        keys.addAll(bodies.keySet());
        for (String k : keys) writePackage(k, specs.get(k), bodies.get(k));
    }

    private void writePackage(String key, PlsqlUnit spec, PlsqlUnit body) throws IOException {
        String path = ctx.pkgPath.get(key);
        PlsqlUnit main = spec != null ? spec : body;
        StringBuilder b = new StringBuilder("# Package ").append(main.qualifiedName()).append("\n\n");
        List<List<String>> meta = new ArrayList<>();
        if (spec != null) meta.add(List.of("Spec", Md.code(spec.file + ":" + spec.line + "-" + spec.endLine)));
        if (body != null) meta.add(List.of("Body", Md.code(body.file + ":" + body.line + "-" + body.endLine)));
        if (spec == null) meta.add(List.of("Spec", "**not in the scanned code** — public/private cannot be decided; all routines are marked UNKNOWN visibility"));
        if (body == null) meta.add(List.of("Body", "**not in the scanned code**"));
        if (main.authid != null) meta.add(List.of("Rights", Md.code(main.authid)));
        b.append(Md.table(List.of("", ""), meta)).append('\n');
        if (spec != null && !spec.docComments.isEmpty()) b.append("## Description\n\n").append(Notes.cleanComment(spec.docComments.get(0))).append("\n\n");

        // public API
        List<Routine> routines = new ArrayList<>();
        if (body != null) for (Routine r : body.decls.routines) if (!r.forwardDeclaration) routines.add(r);
        if (spec != null) for (Routine r : spec.decls.routines) if (r.bodyDefLine == null) routines.add(r);
        routines.sort(Comparator.comparing((Routine r) -> r.id == null ? "" : r.id));
        for (String vis : List.of("PUBLIC", "UNKNOWN", "PRIVATE")) {
            List<List<String>> rows = new ArrayList<>();
            for (Routine r : routines) {
                if (!vis.equals(r.visibility) || r.id == null) continue;
                Routine sp = ctx.specRoutine(r);
                rows.add(List.of(ctx.routineLink(path, r.id), Md.code(r.signature),
                        Notes.purpose(r, sp).replaceFirst(" — no documentation comment in source$", ""),
                        r.hasBody ? "cc " + r.metrics.cyclomatic + " / " + r.metrics.linesOfCode + " loc" : "decl only"));
            }
            if (rows.isEmpty()) continue;
            b.append("## ").append(vis.equals("PUBLIC") ? "Public routines" : vis.equals("PRIVATE") ? "Private routines" : "Routines (visibility unknown — spec not scanned)")
                    .append(" (").append(rows.size()).append(")\n\n").append(Md.table(List.of("Routine", "Signature", "Purpose", "Size"), rows)).append('\n');
        }

        declarations(b, "Declarations in the spec", spec);
        declarations(b, "Declarations in the body", body);

        if (body != null && body.hasInitBlock) {
            b.append("## Initialisation block\n\nRuns once per session when the package is first referenced.");
            if (ctx.unitCardPath.containsKey(body)) b.append(" See ").append(Md.mdLink("init block card", path, ctx.unitCardPath.get(body))).append('.');
            b.append("\n\n");
        }

        // dependencies
        Set<String> tablesRead = new TreeSet<>(), tablesWritten = new TreeSet<>(), builtins = new TreeSet<>(), packagesOut = new TreeSet<>(), unresolved = new TreeSet<>();
        for (PlsqlUnit u : new PlsqlUnit[]{spec, body}) {
            if (u == null) continue;
            for (Routine r : u.decls.routines) collect(r, key, tablesRead, tablesWritten, builtins, packagesOut, unresolved);
        }
        b.append("## Dependencies\n\n");
        b.append("- **Reads tables:** ").append(links(path, tablesRead, true)).append('\n');
        b.append("- **Writes tables:** ").append(links(path, tablesWritten, true)).append('\n');
        b.append("- **Calls other packages:** ").append(packagesOut.isEmpty() ? "_none_" : packagesOut.stream().map(p -> ctx.pkgPath.containsKey(p.toLowerCase())
                ? Md.mdLink(p, path, ctx.pkgPath.get(p.toLowerCase())) : Md.code(p)).collect(Collectors.joining(", "))).append('\n');
        b.append("- **Oracle built-ins:** ").append(builtins.isEmpty() ? "_none_" : builtins.stream().map(Md::code).collect(Collectors.joining(", "))).append('\n');
        b.append("- **Unresolved / external:** ").append(unresolved.isEmpty() ? "_none_" : unresolved.stream().map(Md::code).collect(Collectors.joining(", "))).append("\n\n");

        Set<String> callers = new TreeSet<>();
        for (Routine r : routines) if (r.id != null) for (String c : ctx.calledBy.getOrDefault(r.id, Set.of())) if (!c.startsWith(main.name.toUpperCase() + ".")) callers.add(c);
        b.append("## Used by (other code in the scanned folder)\n\n")
                .append(callers.isEmpty() ? "_nothing_\n\n" : Md.list(callers.stream().map(c -> ctx.routineLink(path, c)).toList()) + "\n");

        List<Risk> risks = new ArrayList<>();
        for (Routine r : routines) risks.addAll(r.risks);
        if (!risks.isEmpty()) {
            Map<String, Long> counts = risks.stream().collect(Collectors.groupingBy(r -> r.severity() + " " + r.code(), TreeMap::new, Collectors.counting()));
            b.append("## Risk summary\n\n").append(Md.list(counts.entrySet().stream().map(e -> e.getKey() + " × " + e.getValue()).toList())).append('\n');
        }
        write(path, b.toString());
    }

    private void declarations(StringBuilder b, String title, PlsqlUnit u) {
        if (u == null) return;
        Declarations d = u.decls;
        if (d.variables.isEmpty() && d.types.isEmpty() && d.cursors.isEmpty() && d.exceptions.isEmpty() && d.pragmas.isEmpty()) return;
        b.append("## ").append(title).append("\n\n");
        List<String> l = new ArrayList<>();
        for (Declarations.Variable v : d.variables)
            l.add((v.constant() ? "constant " : "**state variable** ") + Md.code(v.name()) + " " + Md.code(v.type()) + (v.defaultValue() == null ? "" : " := " + Md.code(v.defaultValue())) + " _(L" + v.line() + ")_");
        for (Declarations.TypeDecl t : d.types) l.add("type " + Md.code(t.name()) + " [" + t.kind().toLowerCase().replace('_', ' ') + "] " + Md.code(t.definition()) + " _(L" + t.line() + ")_");
        for (Declarations.CursorDecl c : d.cursors) l.add("cursor " + Md.code(c.name()) + (c.params().isEmpty() ? "" : "(" + c.params().size() + " params)") + (c.query() == null ? "" : ": " + Md.code(c.query())));
        for (Declarations.ExceptionDecl e : d.exceptions) l.add("exception " + Md.code(e.name()) + (e.errorCode() == null ? "" : " — `EXCEPTION_INIT` " + e.errorCode()));
        for (Declarations.PragmaDecl p : d.pragmas) l.add("pragma " + Md.code(p.text()));
        b.append(Md.list(l)).append('\n');
    }

    private void collect(Routine r, String pkgKey, Set<String> read, Set<String> written, Set<String> builtins, Set<String> pkgs, Set<String> unresolved) {
        read.addAll(r.tablesRead);
        written.addAll(r.tablesWritten);
        for (CallSite c : r.calls) {
            if (CallSite.BUILTIN.equals(c.resolution)) builtins.add(c.builtinPackage);
            else if (CallSite.INTERNAL.equals(c.resolution) && c.targetId != null && !c.targetId.toLowerCase().startsWith(pkgKey + ".")) pkgs.add(c.targetId.substring(0, c.targetId.indexOf('.')));
            else if (CallSite.EXTERNAL.equals(c.resolution) || CallSite.UNRESOLVED.equals(c.resolution) || CallSite.UNRESOLVED_IN_PACKAGE.equals(c.resolution)) unresolved.add(c.callee);
        }
        for (Routine n : r.locals.routines) collect(n, pkgKey, read, written, builtins, pkgs, unresolved);
    }

    private String links(String path, Set<String> tables, boolean isTable) {
        return tables.isEmpty() ? "_none_" : tables.stream().map(t -> ctx.tableLink(path, t)).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ tables

    void writeTables() throws IOException {
        Map<String, List<String>> triggersOn = new TreeMap<>();
        for (PlsqlUnit u : ctx.model.units) {
            if (!u.kind.equals("TRIGGER") || u.triggerHeader == null) continue;
            var m = java.util.regex.Pattern.compile("(?i)\\bon\\s+(?:nested\\s+table\\s+\\S+\\s+of\\s+)?([\\w$#.\"]+)").matcher(u.triggerHeader);
            if (m.find()) triggersOn.computeIfAbsent(m.group(1).toUpperCase().replace("\"", ""), k -> new ArrayList<>()).add(u.name);
        }
        for (String table : ctx.graph.tables()) {
            String path = ctx.tablePath.get(table);
            List<DocContext.TableUse> uses = ctx.tableUses.getOrDefault(table, List.of());
            StringBuilder b = new StringBuilder("# Table / view ").append(table).append("\n\n");
            b.append("Referenced from SQL in the scanned code. Whether this is a table, view or synonym cannot be decided without the database dictionary.\n\n");

            Set<String> readCols = new TreeSet<>(), writeCols = new TreeSet<>(), kinds = new TreeSet<>();
            for (DocContext.TableUse u : uses) {
                readCols.addAll(u.readCols());
                writeCols.addAll(u.writeCols());
                kinds.addAll(u.access());
            }
            b.append("- **Access kinds:** ").append(kinds.isEmpty() ? "_none_" : String.join(", ", kinds)).append('\n');
            b.append("- **Columns read:** ").append(readCols.isEmpty() ? "_none attributed_" : Md.code(String.join(", ", readCols))).append('\n');
            b.append("- **Columns written:** ").append(writeCols.isEmpty() ? "_none_" : Md.code(String.join(", ", writeCols))).append('\n');
            List<String> trg = triggersOn.getOrDefault(table.substring(table.lastIndexOf('.') + 1), List.of());
            if (!trg.isEmpty()) b.append("- **Triggers in scanned code:** ").append(trg.stream().map(t -> Md.code(t)).collect(Collectors.joining(", "))).append('\n');
            b.append('\n');

            DependencyGraph.TableImpact impact = ctx.graph.impactOf(table);
            b.append("## Who touches it\n\n");
            List<List<String>> rows = new ArrayList<>();
            for (DocContext.TableUse u : uses)
                rows.add(List.of(ctx.routineLink(path, u.routineId()), Md.code(Md.fileName(u.file()) + ":" + u.line()), String.join("/", u.access()),
                        (u.inLoop() ? "in loop " : "") + (u.dynamic() ? "dynamic " : "") + ("INFERRED".equals(u.confidence()) ? "inferred" : "")));
            b.append(rows.isEmpty() ? "_No SQL references._\n\n" : Md.table(List.of("Routine", "Where", "Access", "Notes"), rows) + "\n");

            b.append("## Impact\n\n");
            b.append("- **Direct writers:** ").append(impact.writers().isEmpty() ? "_none_" : impact.writers().stream().map(x -> ctx.routineLink(path, x)).collect(Collectors.joining(", "))).append('\n');
            b.append("- **Readers:** ").append(impact.readers().isEmpty() ? "_none_" : impact.readers().stream().map(x -> ctx.routineLink(path, x)).collect(Collectors.joining(", "))).append('\n');
            b.append("- **Reach a writer through calls:** ").append(impact.transitiveWriters().isEmpty() ? "_none_" : impact.transitiveWriters().stream().map(x -> ctx.routineLink(path, x)).collect(Collectors.joining(", "))).append("\n");
            write(path, b.toString());
        }
    }

    private void write(String rel, String content) throws IOException {
        Path p = out.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
