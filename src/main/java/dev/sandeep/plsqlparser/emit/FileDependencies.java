package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Which source file needs which: calls across files, spec↔body pairs and tables two files both touch; plus a callee-first reading order. */
final class FileDependencies {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record FileNode(String file, int lines, List<String> units, int routines, List<String> dependsOn, List<String> usedBy) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record FileEdge(String from, String to, String type, int count, List<String> evidence) {}

    record Result(List<FileNode> files, List<FileEdge> edges, List<String> order, List<List<String>> cycles) {}

    private FileDependencies() {}

    static Result compute(DocContext ctx) {
        StructureModel m = ctx.model;
        DependencyGraph g = ctx.graph;
        Map<String, Integer> lines = new TreeMap<>();
        for (var f : m.files) if (f.variant().equals("primary")) lines.put(f.file(), f.lines());
        Map<String, List<String>> units = new TreeMap<>();
        Map<String, Integer> routines = new TreeMap<>();
        for (PlsqlUnit u : m.units) units.computeIfAbsent(u.file, k -> new ArrayList<>()).add(u.kind.toLowerCase().replace('_', ' ') + " " + u.name);
        for (var n : g.nodes.values()) if (n.type().equals("ROUTINE") && n.file() != null) routines.merge(n.file(), 1, Integer::sum);

        Map<String, int[]> callCount = new TreeMap<>();
        Map<String, Set<String>> callEvidence = new TreeMap<>();
        for (DependencyGraph.Edge e : g.edges()) {
            if (!e.type.equals("CALLS")) continue;
            var a = g.nodes.get(e.from);
            var b = g.nodes.get(e.to);
            if (a == null || b == null || a.file() == null || b.file() == null || a.file().equals(b.file())) continue;
            String k = a.file() + "\n" + b.file();
            callCount.computeIfAbsent(k, x -> new int[1])[0] += Math.max(1, e.count);
            callEvidence.computeIfAbsent(k, x -> new TreeSet<>()).add(e.from + " → " + e.to);
        }
        List<FileEdge> edges = new ArrayList<>();
        for (var en : callCount.entrySet()) {
            String[] ft = en.getKey().split("\n");
            edges.add(new FileEdge(ft[0], ft[1], "CALLS", en.getValue()[0], evidence(callEvidence.get(en.getKey()))));
        }

        Map<String, PlsqlUnit> specs = new TreeMap<>();
        for (PlsqlUnit u : m.units) if (u.kind.equals("PACKAGE_SPEC")) specs.put(u.key(), u);
        for (PlsqlUnit u : m.units) {
            PlsqlUnit s = u.kind.equals("PACKAGE_BODY") ? specs.get(u.key()) : null;
            if (s != null && !s.file.equals(u.file)) edges.add(new FileEdge(u.file, s.file, "SPEC_BODY", 1, List.of(u.name + " body implements the spec in " + s.file)));
        }

        Map<String, Map<String, Set<String>>> tableFiles = new TreeMap<>(); // table -> file -> access kinds
        for (var te : ctx.tableUses.entrySet())
            for (DocContext.TableUse u : te.getValue())
                tableFiles.computeIfAbsent(te.getKey(), k -> new TreeMap<>()).computeIfAbsent(u.file(), k -> new TreeSet<>()).addAll(u.access());
        Map<String, Set<String>> shared = new TreeMap<>();
        for (var te : tableFiles.entrySet()) {
            List<String> fs = new ArrayList<>(te.getValue().keySet());
            for (int i = 0; i < fs.size(); i++)
                for (int j = i + 1; j < fs.size(); j++) {
                    boolean write = !te.getValue().get(fs.get(i)).stream().allMatch(x -> x.equals("READ") || x.equals("LOCK"))
                            || !te.getValue().get(fs.get(j)).stream().allMatch(x -> x.equals("READ") || x.equals("LOCK"));
                    if (write) shared.computeIfAbsent(fs.get(i) + "\n" + fs.get(j), k -> new TreeSet<>()).add(te.getKey());
                }
        }
        // code that touches a table needs the file that creates it
        Map<String, String> ddlFile = new TreeMap<>();
        for (TableDef d : m.tableDefs) if (!d.alterOnly) ddlFile.putIfAbsent(d.key(), d.file);
        Map<String, Set<String>> needs = new TreeMap<>();
        for (var te : tableFiles.entrySet()) {
            String defFile = ddlFile.get(te.getKey());
            if (defFile == null) defFile = ddlFile.entrySet().stream().filter(x -> x.getKey().endsWith("." + te.getKey()) || te.getKey().endsWith("." + x.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
            if (defFile == null) continue;
            for (String f : te.getValue().keySet()) if (!f.equals(defFile)) needs.computeIfAbsent(f + "\n" + defFile, k -> new TreeSet<>()).add(te.getKey());
        }
        for (var en : needs.entrySet()) {
            String[] ft = en.getKey().split("\n");
            edges.add(new FileEdge(ft[0], ft[1], "NEEDS_DDL", en.getValue().size(), evidence(en.getValue())));
        }
        for (var en : shared.entrySet()) {
            String[] ft = en.getKey().split("\n");
            edges.add(new FileEdge(ft[0], ft[1], "SHARED_TABLE", en.getValue().size(), evidence(en.getValue())));
        }
        edges.sort(Comparator.comparing(FileEdge::type).thenComparing(FileEdge::from).thenComparing(FileEdge::to));

        Map<String, Set<String>> dep = new TreeMap<>(), usedBy = new TreeMap<>();
        for (FileEdge e : edges) if (e.type().equals("CALLS") || e.type().equals("SPEC_BODY") || e.type().equals("NEEDS_DDL")) {
            dep.computeIfAbsent(e.from(), k -> new TreeSet<>()).add(e.to());
            usedBy.computeIfAbsent(e.to(), k -> new TreeSet<>()).add(e.from());
        }
        List<FileNode> nodes = new ArrayList<>();
        for (var f : lines.entrySet())
            nodes.add(new FileNode(f.getKey(), f.getValue(), units.getOrDefault(f.getKey(), List.of()), routines.getOrDefault(f.getKey(), 0),
                    new ArrayList<>(dep.getOrDefault(f.getKey(), Set.of())), new ArrayList<>(usedBy.getOrDefault(f.getKey(), Set.of()))));

        List<List<String>> cycles = new ArrayList<>();
        List<String> order = order(lines.keySet(), dep, cycles);
        return new Result(nodes, edges, order, cycles);
    }

    private static List<String> evidence(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() > 6 ? new ArrayList<>(l.subList(0, 6)) : l;
    }

    /** Dependencies first. Files in a cycle are placed together (and reported) instead of looping forever. */
    private static List<String> order(Set<String> files, Map<String, Set<String>> dep, List<List<String>> cycles) {
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>(), visiting = new LinkedHashSet<>();
        for (String f : files) visit(f, dep, done, visiting, out, cycles);
        return out;
    }

    private static void visit(String f, Map<String, Set<String>> dep, Set<String> done, Set<String> visiting, List<String> out, List<List<String>> cycles) {
        if (done.contains(f)) return;
        if (!visiting.add(f)) {
            List<String> path = new ArrayList<>(visiting);
            List<String> cyc = new ArrayList<>(path.subList(path.indexOf(f), path.size()));
            Collections.sort(cyc);
            if (!cycles.contains(cyc)) cycles.add(cyc);
            return;
        }
        for (String d : dep.getOrDefault(f, Set.of())) visit(d, dep, done, visiting, out, cycles);
        visiting.remove(f);
        done.add(f);
        out.add(f);
    }

    // ------------------------------------------------------------------ output

    static void write(DocContext ctx, Path outDir, Result r) throws IOException {
        StringBuilder b = new StringBuilder("# File dependencies\n\nHow the scanned source files relate. A file **depends on** another when its code calls routines defined there, when it is a package body whose spec lives elsewhere, or when it uses tables created there. ")
                .append("Two files also **share a table** when both touch it and at least one writes it: they are not call-linked but a change to one can affect the other.\n\n");
        b.append("## Reading order (dependencies first)\n\n");
        int i = 1;
        for (String f : r.order()) b.append(i++).append(". `").append(f).append("`\n");
        if (!r.cycles().isEmpty()) b.append("\n**Mutually dependent files** (read together): ").append(r.cycles().stream().map(c -> String.join(" ↔ ", c.stream().map(Md::code).toList())).collect(Collectors.joining("; "))).append("\n");
        b.append("\n## Files\n\n").append(Md.table(List.of("File", "Lines", "Units", "Routines", "Depends on", "Used by"), r.files().stream().map(f -> List.of(
                Md.code(f.file()), String.valueOf(f.lines()), String.join(", ", f.units()), String.valueOf(f.routines()),
                f.dependsOn().isEmpty() ? "—" : f.dependsOn().stream().map(Md::code).collect(Collectors.joining(", ")),
                f.usedBy().isEmpty() ? "—" : f.usedBy().stream().map(Md::code).collect(Collectors.joining(", ")))).toList())).append('\n');
        b.append("## Relations\n\n");
        b.append(r.edges().isEmpty() ? "_The files are independent of each other._\n\n" : Md.table(List.of("From", "Relation", "To", "Weight", "Evidence"), r.edges().stream().map(e -> List.of(
                Md.code(e.from()), e.type().equals("CALLS") ? "calls" : e.type().equals("SPEC_BODY") ? "implements spec in" : e.type().equals("NEEDS_DDL") ? "uses tables created in" : "shares table with", Md.code(e.to()),
                String.valueOf(e.count()), String.join("; ", e.evidence().stream().map(Md::code).toList()))).toList()) + "\n");
        b.append("## Diagram\n\nSolid = calls / spec-body, dotted = shared table.\n\n```mermaid\nflowchart LR\n");
        Map<String, String> id = new LinkedHashMap<>();
        int n = 0;
        for (FileNode f : r.files()) id.put(f.file(), "F" + n++);
        for (FileNode f : r.files()) b.append("  ").append(id.get(f.file())).append("[\"").append(f.file().replace("\"", "'")).append("\"]\n");
        for (FileEdge e : r.edges()) {
            String a = id.get(e.from()), c = id.get(e.to());
            if (a == null || c == null) continue;
            b.append("  ").append(a).append(e.type().equals("SHARED_TABLE") ? " -.->|" + e.count() + " table(s)| " : " -->|" + (e.type().equals("CALLS") ? e.count() + " call(s)" : e.type().equals("NEEDS_DDL") ? "tables" : "spec") + "| ").append(c).append('\n');
        }
        b.append("```\n");
        Path md = outDir.resolve("analysis/file-dependencies.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, b.toString());
        Files.writeString(outDir.resolve("analysis/file-dependencies.json"),
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(r));
    }
}
