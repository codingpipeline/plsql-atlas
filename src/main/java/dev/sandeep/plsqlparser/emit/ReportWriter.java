package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.analyze.Confidence;
import dev.sandeep.plsqlparser.analyze.Coverage;
import dev.sandeep.plsqlparser.analyze.Coverage.FileCoverage;
import dev.sandeep.plsqlparser.analyze.MermaidWriter;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.StructureModel.Issue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Writes AGENTS.md, OVERVIEW.md, INDEX.json and the analysis/ reports. */
final class ReportWriter {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");

    private final DocContext ctx;
    private final Path out;
    final List<FileCoverage> coverage;
    final Confidence.Report confidence;
    private final Map<String, Integer> routineConfidence = new HashMap<>();
    /** Filled by {@link #writeCoverage()}; read by the Verifier. */
    int roundTripExact, roundTripViaIf;
    List<String> roundTripBadIds = List.of();
    FileDependencies.Result fileDeps;

    void writeFileDependencies() throws IOException {
        fileDeps = FileDependencies.compute(ctx);
        FileDependencies.write(ctx, out, fileDeps);
    }

    ReportWriter(DocContext ctx, Path out) {
        this.ctx = ctx;
        this.out = out;
        this.coverage = Coverage.compute(ctx.model, ctx.fileText);
        this.confidence = Confidence.compute(ctx.model, coverage);
        for (Confidence.Score s : confidence.routines()) routineConfidence.put(s.id(), s.score());
    }

    // ------------------------------------------------------------------ analysis/confidence.*

    void writeConfidence() throws IOException {
        write("analysis/confidence.json", JSON.writeValueAsString(confidence));
        var c = confidence;
        StringBuilder b = new StringBuilder("# Parse confidence and missing artifacts\n\n");
        b.append("**Overall: ").append(c.overall()).append("/100 (grade ").append(c.grade()).append(")** — routines ").append(c.routineAverage())
                .append(", files ").append(c.fileAverage()).append(". Every deducted point has a reason below; the score says how far the *analysis* can be trusted, not how good the code is.\n\n");
        b.append("Grades: A ≥ 90 · B ≥ 75 · C ≥ 60 · D < 60.\n\n## Least certain routines\n\n");
        var worst = c.routines().stream().filter(s -> s.score() < 100).limit(15).toList();
        b.append(worst.isEmpty() ? "_Every routine scored 100._\n\n" : Md.table(List.of("Routine", "Score", "Why"), worst.stream().map(s -> List.of(
                ctx.cardPath.containsKey(s.id()) ? ctx.routineLink("analysis/confidence.md", s.id()) : Md.code(s.id()), s.score() + " " + s.grade(),
                s.findings().stream().map(f -> f.code() + (f.line() > 0 ? " @" + f.line() : "")).distinct().limit(4).collect(Collectors.joining(", ")))).toList()) + "\n");
        b.append("## Missing artifacts\n\nObjects the code relies on that the scanned files do not contain (or that were seen but not decomposed). Ask the owner for these before relying on the affected routines.\n\n");
        b.append(c.missing().isEmpty() ? "_Nothing missing._\n" : Md.table(List.of("Severity", "Kind", "Name", "What it means", "Used by"), c.missing().stream().map(x -> List.of(
                x.severity(), x.kind(), Md.code(x.name()), x.detail(), x.usedBy().stream().limit(4).map(id -> ctx.cardPath.containsKey(id) ? ctx.routineLink("analysis/confidence.md", id) : Md.code(id))
                        .collect(Collectors.joining(", ")) + (x.usedBy().size() > 4 ? ", …" : ""))).toList()));
        write("analysis/confidence.md", b.toString());
    }

    // ------------------------------------------------------------------ AGENTS.md

    void writeAgentsMd() throws IOException {
        StructureModel m = ctx.model;
        StringBuilder b = new StringBuilder("# Agent guide — read this first\n\n");
        b.append("This folder is a **machine-generated, lossless description of Oracle PL/SQL** found in `source/`. ")
                .append("It was produced by static analysis (ANTLR parse tree) — no database, no LLM. ")
                .append("Use it to understand the code well enough to refactor or port it to any target. ")
                .append("Nothing here assumes a target language.\n\n");
        b.append("## Reading order\n\n");
        b.append("1. [OVERVIEW.md](OVERVIEW.md) — what is in the code base, dependency diagram, entry points, hot spots.\n");
        b.append("2. [INDEX.json](INDEX.json) — every routine with purpose, size, risks, calls and conversion batch (query this, don't read every card).\n");
        b.append("3. [analysis/file-dependencies.md](analysis/file-dependencies.md) — how the source files relate and in which order to read them; then [analysis/migration-order.md](analysis/migration-order.md) — callees-before-callers batches and recursion cycles.\n");
        b.append("4. `objects/<package>/_package.md` — public API, state, declarations, dependencies of one package.\n");
        b.append("5. `objects/<package>/<routine>.md` (or `.json`) — one **routine card**: everything needed to work on that routine without opening the rest.\n");
        b.append("6. `tables/<table>.md` — who reads/writes a table, which columns; [analysis/er-diagram.md](analysis/er-diagram.md) — entity-relationship diagram (declared foreign keys vs relationships inferred from joins).\n");
        b.append("7. [analysis/risks.md](analysis/risks.md) and [analysis/coverage.md](analysis/coverage.md) — hazards and the proof of completeness.\n");
        b.append("8. [analysis/verification.md](analysis/verification.md) — the tool's own cross-check verdict; [analysis/confidence.md](analysis/confidence.md) — how certain each routine's analysis is and what is missing.\n\n");

        b.append("## Working method for a refactoring agent\n\n");
        b.append("- Work **one routine card at a time**, in batch order (batch 0 first). A card is self-contained: signature, numbered logic outline, every SQL statement, calls, side effects, exceptions, Oracle semantics notes, original source.\n");
        b.append("- The **logic outline is lossless for control flow**: every IF/ELSIF/CASE branch, loop, EXIT/CONTINUE/GOTO, RAISE, RETURN, handler and transaction statement is a numbered step. Port every step; if you drop one, say why.\n");
        b.append("- When the outline and the original source disagree, **the source in the card wins** and the discrepancy is a bug in this tool — report it.\n");
        b.append("- **Never guess unresolved things.** Items marked `UNRESOLVED`, `EXTERNAL_UNRESOLVED`, `UNKNOWN`, `PARTIAL`, `INFERRED` or listed under *Open questions* need a human or more source.\n");
        b.append("- Preserve Oracle semantics called out in *Oracle semantics notes* (NULL vs empty string, `NO_DATA_FOUND`, collections, bulk operations, cursor attributes, date arithmetic, autonomous transactions).\n");
        b.append("- Transaction boundaries (`COMMIT`/`ROLLBACK`/autonomous) and package state are *behaviour*, not details: reproduce or consciously redesign them.\n\n");

        b.append("## Legend\n\n");
        b.append("**Ids** — `PKG.ROUTINE`; overloads `PKG.ROUTINE#2` (order of appearance); nested subprograms `PKG.OUTER>INNER`; standalone `NAME`; alternate-variant-only routines end in `~ALT`.\n\n");
        b.append("**Visibility** — `PUBLIC` (declared in a package spec), `PRIVATE` (body only), `UNKNOWN` (spec not scanned), `STANDALONE`, `LOCAL` (nested).\n\n");
        b.append("**Outline step kinds** — `ASSIGN` `IF`/`THEN`/`ELSIF`/`ELSE` `CASE`/`WHEN` `LOOP` `WHILE_LOOP` `FOR_LOOP` `CURSOR_FOR_LOOP` `FORALL` `EXIT` `CONTINUE` `GOTO` `LABEL` `NULL` `RETURN` `RAISE` `CALL` `SQL` `DYNAMIC_SQL` `OPEN_CURSOR` `FETCH` `CLOSE_CURSOR` `OPEN_FOR` `COMMIT` `ROLLBACK` `SAVEPOINT` `SET_TRANSACTION` `PIPE_ROW` `COLLECTION_OP` `BLOCK` `EXCEPTION_SECTION` `HANDLER` `PRAGMA` `GRANT`.\n\n");
        b.append("**Call resolution** — `INTERNAL` (routine in these files), `LOCAL` (nested), `BUILTIN` (Oracle-supplied package), `EXTERNAL_UNRESOLVED` (package not in these files), `UNRESOLVED_IN_KNOWN_PACKAGE`, `UNRESOLVED`. Overloads that argument *count* cannot separate are listed as candidates.\n\n");
        b.append("**SQL access** — `READ` `INSERT` `UPDATE` `DELETE` `MERGE` `LOCK` `DDL`. Dynamic SQL confidence: `LITERAL` (fully literal, parsed), `PARTIAL` (tables inferred from fragments), `UNKNOWN`.\n\n");
        b.append("**Line references** — `file:line` always refer to the copies in `source/` (identical to the originals; code listings in cards carry file line numbers).\n\n");
        b.append("**Conditional compilation** — `$IF` blocks are analysed twice: *primary* (undefined `$$flags` are NULL, as in Oracle) and *alternate* (undefined flags TRUE). Routines that exist only in the alternate variant carry the `~ALT` id suffix.\n\n");

        b.append("## This run\n\n");
        b.append(Md.table(List.of("What", "Count"), List.of(
                List.of("Source files", String.valueOf(sourceFiles().size())),
                List.of("Packages", String.valueOf(ctx.pkgPath.size())),
                List.of("Routines with a card", String.valueOf(ctx.cardPath.size())),
                List.of("Tables / views referenced", String.valueOf(ctx.tablePath.size())),
                List.of("Risks (HIGH / MEDIUM / LOW / INFO)", riskCounts()),
                List.of("Parse confidence", confidence.overall() + "/100 (" + confidence.grade() + "), " + confidence.missing().size() + " missing artifact(s) — see analysis/confidence.md"),
                List.of("Syntax errors", String.valueOf(m.files.stream().mapToInt(StructureModel.FileInfo::syntaxErrors).sum())),
                List.of("Unaccounted code lines", String.valueOf(coverage.stream().mapToInt(FileCoverage::unaccountedCount).sum())))));
        b.append("\nGenerated by plsql-atlas ").append(ctx.toolVersion).append(", created by Sandeep Ravitej. Regenerate instead of editing by hand.\n");
        write("AGENTS.md", b.toString());
    }

    private String riskCounts() {
        Map<String, Long> c = ctx.model.risks.stream().collect(Collectors.groupingBy(Risk::severity, Collectors.counting()));
        return SEVERITIES.stream().map(s -> String.valueOf(c.getOrDefault(s, 0L))).collect(Collectors.joining(" / "));
    }

    private List<String> sourceFiles() {
        return ctx.model.files.stream().map(StructureModel.FileInfo::file).distinct().sorted().toList();
    }

    // ------------------------------------------------------------------ OVERVIEW.md

    void writeOverview() throws IOException {
        StructureModel m = ctx.model;
        StringBuilder b = new StringBuilder("# Overview\n\n");
        List<List<String>> rows = new ArrayList<>();
        for (String key : ctx.pkgPath.keySet()) {
            PlsqlUnit spec = null, body = null;
            for (PlsqlUnit u : m.units) if (u.key().equals(key)) { if (u.kind.equals("PACKAGE_SPEC")) spec = u; else if (u.kind.equals("PACKAGE_BODY")) body = u; }
            PlsqlUnit main = spec != null ? spec : body;
            List<Routine> rs = new ArrayList<>();
            if (body != null) rs.addAll(body.decls.routines.stream().filter(r -> !r.forwardDeclaration).toList());
            if (spec != null) rs.addAll(spec.decls.routines.stream().filter(r -> r.bodyDefLine == null).toList());
            long pub = rs.stream().filter(r -> "PUBLIC".equals(r.visibility)).count();
            int loc = rs.stream().mapToInt(r -> r.metrics.linesOfCode).sum();
            int maxCc = rs.stream().mapToInt(r -> r.metrics.cyclomatic).max().orElse(0);
            Set<String> tr = new TreeSet<>(), tw = new TreeSet<>();
            for (Routine r : rs) { tr.addAll(r.tablesRead); tw.addAll(r.tablesWritten); }
            long hi = rs.stream().flatMap(r -> r.risks.stream()).filter(r -> r.severity().equals("HIGH")).count();
            rows.add(List.of(Md.mdLink(main.name, "OVERVIEW.md", ctx.pkgPath.get(key)), String.valueOf(rs.size()), pub + " public", loc + " loc", "max cc " + maxCc,
                    "R " + tr.size() + " / W " + tw.size(), hi == 0 ? "" : hi + " HIGH risk"));
        }
        b.append("## Packages\n\n").append(rows.isEmpty() ? "_No packages._\n\n" : Md.table(List.of("Package", "Routines", "API", "Size", "Complexity", "Tables", "Hot spots"), rows) + "\n");

        List<Routine> standalone = new ArrayList<>();
        for (PlsqlUnit u : m.units) if (u.kind.equals("PROCEDURE") || u.kind.equals("FUNCTION")) standalone.addAll(u.decls.routines);
        if (!standalone.isEmpty()) {
            b.append("## Standalone routines\n\n");
            b.append(Md.list(standalone.stream().map(r -> ctx.routineLink("OVERVIEW.md", r.id) + " — " + Md.code(r.signature)).toList())).append('\n');
        }
        List<PlsqlUnit> trg = m.units.stream().filter(u -> u.kind.equals("TRIGGER")).toList();
        if (!trg.isEmpty()) {
            b.append("## Triggers\n\n");
            b.append(Md.list(trg.stream().map(u -> Md.mdLink(u.name, "OVERVIEW.md", ctx.unitCardPath.get(u)) + " — " + Md.code(u.triggerHeader == null ? "" : u.triggerHeader)).toList())).append('\n');
        }

        b.append("## Dependency diagram\n\nPackages, tables (cylinders), Oracle built-ins (rounded) and unresolved targets (hexagons). Dotted = reads, thick = writes.\n\n");
        b.append("```mermaid\n").append(MermaidWriter.packageDiagram(ctx.graph)).append("```\n\n");

        b.append("## Tables and views\n\n");
        List<List<String>> trows = new ArrayList<>();
        for (String t : ctx.graph.tables()) {
            var imp = ctx.graph.impactOf(t);
            trows.add(List.of(ctx.tableLink("OVERVIEW.md", t), String.valueOf(imp.readers().size()), String.valueOf(imp.writers().size())));
        }
        b.append(trows.isEmpty() ? "_No SQL table references._\n\n" : Md.table(List.of("Table", "Readers", "Writers"), trows) + "\n");

        b.append("## Entry points\n\nPublic or standalone routines that nothing else in the scanned code calls (external API, scheduler jobs, UI, or dead code):\n\n");
        List<String> entries = ctx.graph.uncalledPublicRoutines();
        b.append(entries.isEmpty() ? "_none_\n\n" : Md.list(entries.stream().map(e -> ctx.routineLink("OVERVIEW.md", e)).toList()) + "\n");
        List<String> dead = ctx.graph.uncalledPrivateRoutines();
        if (!dead.isEmpty()) b.append("Private routines never called (dead-code candidates):\n\n").append(Md.list(dead.stream().map(e -> ctx.routineLink("OVERVIEW.md", e)).toList())).append('\n');

        List<Routine> hot = ctx.routineById.values().stream().filter(r -> r.hasBody).sorted(Comparator.comparingInt((Routine r) -> -r.metrics.cyclomatic).thenComparing(r -> r.id)).limit(10).toList();
        b.append("## Most complex routines\n\n").append(Md.table(List.of("Routine", "Cyclomatic", "Lines", "Risks"), hot.stream()
                .map(r -> List.of(ctx.routineLink("OVERVIEW.md", r.id), String.valueOf(r.metrics.cyclomatic), String.valueOf(r.metrics.linesOfCode), String.valueOf(r.risks.size()))).toList())).append('\n');

        b.append("## Source files\n\n").append(Md.list(sourceFiles().stream().map(f -> Md.code(f)).toList())).append('\n');
        write("OVERVIEW.md", b.toString());
    }

    // ------------------------------------------------------------------ INDEX.json

    void writeIndex() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Routine r : ctx.routineById.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            PlsqlUnit u = ctx.unitOf.get(r);
            m.put("id", r.id);
            m.put("kind", r.kind);
            m.put("package", u != null && u.kind.startsWith("PACKAGE") ? u.name : null);
            m.put("visibility", r.visibility);
            m.put("signature", r.signature);
            m.put("purpose", Notes.purpose(r, ctx.specRoutine(r)));
            m.put("file", r.file);
            m.put("line", r.line);
            m.put("endLine", r.endLine);
            m.put("hasBody", r.hasBody);
            m.put("alternateVariantOnly", ctx.alternateOnlyIds.contains(r.id));
            m.put("card", ctx.cardPath.get(r.id));
            m.put("cardJson", ctx.cardPath.get(r.id).replaceFirst("\\.md$", ".json"));
            m.put("batch", ctx.order.batchOf().get(r.id));
            m.put("rank", ctx.order.rankOf().get(r.id));
            m.put("cyclomatic", r.metrics.cyclomatic);
            m.put("linesOfCode", r.metrics.linesOfCode);
            m.put("attributes", r.attributes);
            m.put("tablesRead", r.tablesRead);
            m.put("tablesWritten", r.tablesWritten);
            m.put("calls", r.calls.stream().map(c -> c.targetId != null ? c.targetId : c.callee).distinct().sorted().toList());
            m.put("calledBy", ctx.calledBy.getOrDefault(r.id, Set.of()));
            m.put("risks", r.risks.stream().map(k -> k.severity() + ":" + k.code()).distinct().sorted().toList());
            items.add(m);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tool", "plsql-atlas " + ctx.toolVersion);
        doc.put("routines", items);
        doc.put("packages", ctx.pkgPath);
        doc.put("tables", ctx.tablePath);
        doc.put("unitBlocks", ctx.unitCardPath.entrySet().stream().map(e -> Map.of("name", e.getKey().name, "kind", e.getKey().kind, "card", e.getValue())).sorted(Comparator.comparing(x -> x.get("card"))).toList());
        write("INDEX.json", JSON.writeValueAsString(doc));
    }

    // ------------------------------------------------------------------ analysis/graph.json (read by the Explorer)

    /** Nodes (routines enriched with metrics, tables, built-ins, unresolved targets), edges, table impact and coverage per file. */
    void writeGraph() throws IOException {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var n : ctx.graph.nodes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("type", n.type());
            m.put("label", n.type().equals("ROUTINE") ? n.id() : n.label());
            m.put("owner", n.owner());
            m.put("file", n.file());
            m.put("line", n.line());
            m.put("visibility", n.visibility());
            Routine r = ctx.routineById.get(n.id());
            if (r != null) {
                m.put("endLine", r.endLine);
                m.put("cyclomatic", r.metrics.cyclomatic);
                m.put("linesOfCode", r.metrics.linesOfCode);
                m.put("batch", ctx.order.batchOf().get(r.id));
                m.put("rank", ctx.order.rankOf().get(r.id));
                m.put("risks", r.risks.stream().map(k -> k.severity() + ":" + k.code()).distinct().sorted().toList());
                m.put("confidence", routineConfidence.get(r.id));
                m.put("card", ctx.cardPath.get(r.id));
                m.put("cardJson", ctx.cardPath.get(r.id).replaceFirst("\\.md$", ".json"));
            } else if (n.type().equals("TABLE")) {
                m.put("card", ctx.tablePath.get(n.label()));
            }
            nodes.add(m);
        }
        Map<String, Object> impact = new TreeMap<>();
        for (String t : ctx.graph.tables()) impact.put(t, ctx.graph.impactOf(t));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tool", "plsql-atlas " + ctx.toolVersion);
        doc.put("nodes", nodes);
        doc.put("edges", ctx.graph.edges());
        doc.put("recursionGroups", ctx.graph.recursionGroups());
        doc.put("uncalledPrivateRoutines", ctx.graph.uncalledPrivateRoutines());
        doc.put("uncalledPublicRoutines", ctx.graph.uncalledPublicRoutines());
        doc.put("tableImpact", impact);
        doc.put("batches", ctx.order.batches());
        doc.put("cycles", ctx.order.cycles());
        doc.put("coverage", coverage);
        if (fileDeps != null) {
            doc.put("fileNodes", fileDeps.files());
            doc.put("fileEdges", fileDeps.edges());
            doc.put("fileOrder", fileDeps.order());
            doc.put("fileCycles", fileDeps.cycles());
        }
        write("analysis/graph.json", JSON.writeValueAsString(doc));
    }

    // ------------------------------------------------------------------ analysis/*

    void writeMigrationOrder() throws IOException {
        var o = ctx.order;
        String f = "analysis/migration-order.md";
        StringBuilder b = new StringBuilder("# Understanding / porting order\n\n");
        b.append("Callees come before callers: **batch 0** routines call nothing else in the scanned code; batch *n* calls at least one batch *n-1* routine. ")
                .append("Work through batches in order so every dependency is already understood. This is a neutral dependency order — it says nothing about the target technology.\n\n");
        if (!o.cycles().isEmpty()) {
            b.append("## Mutually recursive groups\n\nThese must be handled together (they are collapsed to one unit for ordering). Groups caused only by ambiguous overload candidates may be false cycles.\n\n");
            for (List<String> c : o.cycles()) b.append("- ").append(String.join(" ↔ ", c.stream().map(x -> ctx.routineLink(f, x)).toList())).append('\n');
            b.append('\n');
        }
        for (int i = 0; i < o.batches().size(); i++) {
            b.append("## Batch ").append(i).append(" (").append(o.batches().get(i).size()).append(")\n\n");
            b.append(Md.table(List.of("Rank", "Routine", "Cyclomatic", "Size", "Calls into scanned code"), o.batches().get(i).stream().map(id -> {
                Routine r = ctx.routineById.get(id);
                return List.of(String.valueOf(o.rankOf().get(id)), ctx.routineLink(f, id), r == null ? "" : String.valueOf(r.metrics.cyclomatic),
                        r == null ? "" : r.metrics.linesOfCode + " loc", r == null ? "" : String.valueOf(r.calls.stream().filter(c -> c.targetId != null).map(c -> c.targetId).distinct().count()));
            }).toList())).append('\n');
        }
        write(f, b.toString());
    }

    void writeRisks() throws IOException {
        String f = "analysis/risks.md";
        StringBuilder b = new StringBuilder("# Risks\n\nOracle-specific hazards found by deterministic rules. Each one is a *fact to account for* when reading or porting, not a verdict.\n\n");
        Map<String, Long> byCode = ctx.model.risks.stream().collect(Collectors.groupingBy(r -> r.severity() + " " + r.code(), TreeMap::new, Collectors.counting()));
        b.append("## Summary\n\n").append(byCode.isEmpty() ? "_No risks detected._\n\n" : Md.list(byCode.entrySet().stream().map(e -> e.getKey() + " × " + e.getValue()).toList()) + "\n");
        for (String sev : SEVERITIES) {
            List<Risk> rs = ctx.model.risks.stream().filter(r -> r.severity().equals(sev)).toList();
            if (rs.isEmpty()) continue;
            b.append("## ").append(sev).append(" (").append(rs.size()).append(")\n\n");
            b.append(Md.table(List.of("Code", "Routine", "Where", "What"), rs.stream().map(r -> List.of(Md.code(r.code()),
                    ctx.cardPath.containsKey(r.routineId()) ? ctx.routineLink(f, r.routineId()) : Md.code(r.routineId() == null ? "" : r.routineId()),
                    Md.code(Md.fileName(r.file()) + (r.line() > 0 ? ":" + r.line() : "")), r.message())).toList())).append('\n');
        }
        write(f, b.toString());
    }

    void writeCoverage() throws IOException {
        StructureModel m = ctx.model;
        StringBuilder b = new StringBuilder("# Coverage — proof that nothing was silently dropped\n\n");
        int unaccounted = coverage.stream().mapToInt(FileCoverage::unaccountedCount).sum();
        b.append("**Result:** ").append(unaccounted == 0 ? "every line of code is accounted for." : "**" + unaccounted + " code line(s) are NOT accounted for** (listed below).").append("\n\n");
        b.append("A code line (comments and blanks excluded) counts as accounted for when it lies inside a recognised unit (package spec/body, routine, trigger, block), inside a *listed* skipped statement (DDL etc.), or is a `/` terminator.\n\n");

        b.append("## Per file\n\n");
        b.append(Md.table(List.of("File", "Code lines", "In units", "In listed skipped statements", "Terminators", "Unaccounted", "Coverage"), coverage.stream().map(c -> List.of(
                Md.code(c.file()), String.valueOf(c.codeLines()), String.valueOf(c.inUnits()), String.valueOf(c.inSkipped()), String.valueOf(c.terminators()),
                c.unaccounted().isEmpty() ? "0" : c.unaccounted().stream().map(r -> r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "-" + r[1]).collect(Collectors.joining(", ")),
                String.format("%.1f%%", c.percent()))).toList())).append('\n');

        b.append("## Parse health\n\n");
        List<StructureModel.FileInfo> primary = m.files.stream().filter(f -> f.variant().equals("primary")).toList();
        b.append(Md.table(List.of("File", "Lines", "Syntax errors", "Undecided `$IF` conditions"), primary.stream().map(f -> List.of(Md.code(f.file()), String.valueOf(f.lines()),
                String.valueOf(f.syntaxErrors()), String.valueOf(f.undecidedConditions()))).toList())).append('\n');
        List<Issue> errs = m.issues.stream().filter(i -> i.severity().equals("ERROR") || i.code().equals("outline-mismatch")).toList();
        if (!errs.isEmpty()) b.append("**Errors**\n\n").append(Md.list(errs.stream().map(i -> "`" + Md.fileName(i.file()) + ":" + i.line() + "` " + i.code() + " — " + i.message()).toList())).append('\n');

        b.append("## Conditional compilation\n\n");
        List<String> cc = new ArrayList<>();
        for (StructureModel.FileInfo f : primary)
            if (!f.undecided().isEmpty()) cc.add(Md.code(f.file()) + ": " + f.undecided().stream().map(Md::code).collect(Collectors.joining(", ")));
        b.append(cc.isEmpty() ? "_No conditions depended on undefined flags._\n\n" : "These conditions depend on flags that are not defined; both variants were analysed. Routines present only in the alternate variant: "
                + (ctx.alternateOnlyIds.isEmpty() ? "_none_" : ctx.alternateOnlyIds.stream().map(id -> ctx.routineLink("analysis/coverage.md", id)).collect(Collectors.joining(", "))) + "\n\n" + Md.list(cc) + "\n");

        b.append("## Listed, not decomposed\n\n");
        b.append(m.skipped.isEmpty() ? "_Nothing skipped._\n\n" : Md.table(List.of("File", "Lines", "Kind", "Statement", "Why"), m.skipped.stream().filter(s -> s.variant().equals("primary")).map(s -> List.of(
                Md.code(Md.fileName(s.file())), s.line() + "-" + s.endLine(), s.kind(), Md.code(s.name()), s.reason())).toList()) + "\n");

        b.append("## Dynamic SQL\n\n");
        List<List<String>> dyn = new ArrayList<>();
        for (Routine r : ctx.routineById.values()) for (SqlStatement s : r.sql) if (s.dynamic)
            dyn.add(List.of(ctx.routineLink("analysis/coverage.md", r.id), String.valueOf(s.line), s.dynamicConfidence, s.dynamicKind == null ? "" : s.dynamicKind,
                    s.tables.stream().map(t -> t.key() + t.access).collect(Collectors.joining(", "))));
        b.append(dyn.isEmpty() ? "_None._\n\n" : Md.table(List.of("Routine", "Line", "Confidence", "Kind", "Tables found"), dyn) + "\n");

        b.append("## Unresolved and ambiguous calls\n\n");
        List<List<String>> unres = new ArrayList<>();
        for (Routine r : ctx.routineById.values()) for (CallSite c : r.calls) {
            if (c.resolution == null || c.resolution.equals(CallSite.INTERNAL) || c.resolution.equals(CallSite.LOCAL) || c.resolution.equals(CallSite.BUILTIN) && c.candidates.isEmpty()) continue;
            unres.add(List.of(ctx.routineLink("analysis/coverage.md", r.id), String.valueOf(c.line), Md.code(c.callee), c.resolution + (c.candidates.isEmpty() ? "" : " (" + c.candidates.size() + " candidates)")));
        }
        for (Routine r : ctx.routineById.values()) for (CallSite c : r.calls)
            if (!c.candidates.isEmpty() && (c.resolution.equals(CallSite.INTERNAL) || c.resolution.equals(CallSite.LOCAL)))
                unres.add(List.of(ctx.routineLink("analysis/coverage.md", r.id), String.valueOf(c.line), Md.code(c.callee), "AMBIGUOUS_OVERLOAD (" + c.candidates.size() + " candidates: " + String.join(", ", c.candidates.stream().limit(3).toList()) + (c.candidates.size() > 3 ? ", …" : "") + ")"));
        b.append(unres.isEmpty() ? "_None._\n\n" : Md.table(List.of("Routine", "Line", "Callee", "Status"), unres) + "\n");

        b.append("## Round-trip check\n\n");
        int exact = 0, viaIf = 0, bad = 0;
        List<String> badIds = new ArrayList<>();
        for (Routine r : ctx.routineById.values()) {
            String parsed = r.source == null ? "" : r.source.replaceAll("\\s+", "");
            String original = sliceOriginal(r).replaceAll("\\s+", "");
            if (parsed.equals(original)) exact++;
            else if (isSubsequence(parsed, original)) viaIf++;   // parser saw the file with $IF branches blanked
            else { bad++; badIds.add(r.id); }
        }
        roundTripExact = exact;
        roundTripViaIf = viaIf;
        roundTripBadIds = badIds;
        b.append("What the parser saw for each routine (its source span) is compared with the original file lines the card embeds: **")
                .append(exact).append(" identical**, ").append(viaIf).append(" identical apart from `$IF`-excluded text, **")
                .append(bad).append(" mismatching**").append(bad > 0 ? " (" + String.join(", ", badIds) + ")" : "").append(".\n");
        write("analysis/coverage.md", b.toString());
    }

    private static boolean isSubsequence(String small, String big) {
        int i = 0;
        for (int k = 0; k < big.length() && i < small.length(); k++) if (big.charAt(k) == small.charAt(i)) i++;
        return i == small.length();
    }

    /** Independent re-read of the routine's lines from the text provider (not from the cached line list). */
    private String sliceOriginal(Routine r) {
        String[] l = ctx.fileText.apply(r.file).split("\r?\n", -1);
        StringBuilder b = new StringBuilder();
        for (int i = r.line; i <= r.endLine && i <= l.length; i++) b.append(l[i - 1]).append('\n');
        return b.toString();
    }

    private void write(String rel, String content) throws IOException {
        Path p = out.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
