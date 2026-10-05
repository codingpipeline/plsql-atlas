package dev.sandeep.plsqlparser.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.analyze.MermaidWriter;
import dev.sandeep.plsqlparser.extract.StructureBuilder;
import dev.sandeep.plsqlparser.model.Risk;
import dev.sandeep.plsqlparser.model.StructureModel;
import dev.sandeep.plsqlparser.parse.Discover;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade.ParsedUnit;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "plsql-atlas", mixinStandardHelpOptions = true, version = {"plsql-atlas 0.1.1", "Created by Sandeep Ravitej"},
        description = "Turn a folder of Oracle PL/SQL into verified, agent-ready documentation. Created by Sandeep Ravitej.",
        subcommands = {Main.GoCmd.class, Main.BuildCmd.class, Main.ExploreCmd.class, Main.Check.class, Main.OutlineCmd.class, Main.GraphCmd.class, Main.RisksCmd.class})
public class Main implements Runnable {
    private static final Set<String> COMMANDS = Set.of("go", "build", "explore", "check", "outline", "graph", "risks", "help");

    /** Shown at the start of every build so each run credits the author. */
    static final String BANNER = "plsql-atlas " + dev.sandeep.plsqlparser.emit.AgentDocs.VERSION + " - created by Sandeep Ravitej";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(route(args)));
    }

    /**
     * No arguments, an option that only {@code go} knows (e.g. {@code --no-open}), or a first argument that is an existing
     * file/folder, means {@code go}: drop the jar next to the PL/SQL files and run it.
     */
    static String[] route(String[] a) {
        if (a.length == 0) return new String[]{"go"};
        boolean rootOption = Set.of("-h", "--help", "-V", "--version").contains(a[0]);
        if (COMMANDS.contains(a[0]) || rootOption) return a;
        if (a[0].startsWith("-") || java.nio.file.Files.exists(Path.of(a[0]))) {
            String[] r = new String[a.length + 1];
            r[0] = "go";
            System.arraycopy(a, 0, r, 1, a.length);
            return r;
        }
        return a;
    }

    /** Prints the verifier verdict the way every command shows it. */
    static void printVerdict(java.io.PrintStream out, dev.sandeep.plsqlparser.emit.Verifier.Report v) {
        out.println();
        out.println(dev.sandeep.plsqlparser.run.Ascii.clean("VERDICT: " + v.verdict().replace('_', ' ') + "  (parse confidence " + v.confidence() + "/100, grade " + v.grade() + ")"));
        out.println(dev.sandeep.plsqlparser.run.Ascii.clean("  " + v.summary()));
        for (var c : v.checks()) if (c.status().equals("FAIL") || c.status().equals("WARN"))
            for (String d : c.details().stream().limit(5).toList()) out.println(dev.sandeep.plsqlparser.run.Ascii.clean("  " + c.status() + ": " + d));
    }

    /** Progress to the console and to the live UI at once. */
    private record Both(dev.sandeep.plsqlparser.run.Progress a, dev.sandeep.plsqlparser.run.Progress b) implements dev.sandeep.plsqlparser.run.Progress {
        @Override public void stage(String id, String detail) { a.stage(id, detail); b.stage(id, detail); }
        @Override public void file(String file, int done, int total, int errors) { a.file(file, done, total, errors); b.file(file, done, total, errors); }
        @Override public void log(String line) { a.log(line); b.log(line); }
        @Override public void finished(String verdict, String message) { a.finished(verdict, message); b.finished(verdict, message); }
    }

    /** M7 default command: scan a folder, write .agentdocs/ next to the files, verify it, and show the live UI. */
    @Command(name = "go", description = "Default. Scan this folder (or the given one), write .agentdocs/ next to the files, cross-check it and open the live UI.",
            mixinStandardHelpOptions = true)
    static class GoCmd implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "folder with the PL/SQL files (default: current folder)") Path folder;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false") List<String> defines = new ArrayList<>();
        @Option(names = {"-o", "--out"}, description = "output folder (default: <folder>/.agentdocs)") Path out;
        @Option(names = "--no-source", description = "do not copy the original files into source/") boolean noSource;
        @Option(names = "--port", defaultValue = "9000", description = "first port to try on 127.0.0.1 (default 9000; the next 99 are tried if busy)") int port;
        @Option(names = "--no-open", description = "do not open the browser") boolean noOpen;
        @Option(names = "--no-ui", description = "build and verify only, no web UI (exit code 1 if not verified)") boolean noUi;
        @Option(names = "--watch", description = "keep running and rebuild when a PL/SQL file changes") boolean watch;

        @Override
        public Integer call() throws Exception {
            System.out.println(BANNER);
            Path abs = folder.toAbsolutePath().normalize();
            Path root = java.nio.file.Files.isDirectory(abs) ? abs : abs.getParent();
            Path outDir = out != null ? out : root.resolve(".agentdocs");
            var flags = Check.flags(defines);
            List<Path> inputs = List.of(abs);
            var console = dev.sandeep.plsqlparser.run.Progress.console(System.out);
            if (noUi) {
                try {
                    var o = dev.sandeep.plsqlparser.run.Pipeline.run(root, inputs, flags, outDir, !noSource, console);
                    summary(o);
                    return o.verdict().equals("NOT_VERIFIED") ? 1 : 0;
                } catch (java.io.IOException e) {
                    System.err.println(e.getMessage());
                    return 1;
                }
            }
            try (var server = dev.sandeep.plsqlparser.explore.ExplorerServer.startOnFirstFreePort(outDir, port, 100, true)) {
                System.out.println("Live UI: " + server.url() + "   (Ctrl+C to stop)");
                if (!noOpen && java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(server.url()));
                var both = new Both(console, server.status());
                Runnable once = () -> {
                    server.status().start(root.toString());
                    try {
                        summary(dev.sandeep.plsqlparser.run.Pipeline.run(root, inputs, flags, outDir, !noSource, both));
                    } catch (java.io.IOException | RuntimeException e) {
                        System.err.println("build failed: " + e.getMessage());
                    }
                };
                once.run();
                if (watch) {
                    System.out.println("Watching " + root + " for changes...");
                    String last = dev.sandeep.plsqlparser.run.Pipeline.fingerprint(root);
                    while (true) {
                        Thread.sleep(1000);
                        String now = dev.sandeep.plsqlparser.run.Pipeline.fingerprint(root);
                        if (now.equals(last)) continue;
                        Thread.sleep(1500); // let an editor finish saving
                        last = dev.sandeep.plsqlparser.run.Pipeline.fingerprint(root);
                        System.out.println("\nChange detected, rebuilding...");
                        once.run();
                    }
                }
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return 0;
        }

        private static void summary(dev.sandeep.plsqlparser.run.Pipeline.Outcome o) {
            var r = o.docs();
            System.out.printf("%nwrote %s%n  %d files | %d packages | %d cards | %d tables | %d risks%n", r.outDir(), r.sourceFiles(), r.packages(), r.cards(), r.tables(), o.model().risks.size());
            printVerdict(System.out, r.verdict());
            System.out.println("\nHand " + r.outDir().resolve("AGENTS.md") + " to your coding agent.");
        }
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** M2 command: extract structure; print an outline and/or write model.json. */
    @Command(name = "outline", description = "Extract structure (units, routines, params, types...) and print an outline.",
            mixinStandardHelpOptions = true)
    static class OutlineCmd implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "PL/SQL files and/or folders") List<Path> paths;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false")
        List<String> defines = new ArrayList<>();
        @Option(names = {"-v", "--verbose"}, description = "include types, cursors, exceptions, comments and line spans")
        boolean verbose;
        @Option(names = {"-o", "--json"}, description = "also write the full structure model to this JSON file")
        Path json;
        @Option(names = "--logic", description = "print the numbered logic outline, exception contract, side effects, metrics and risks per routine")
        boolean logic;

        @Override
        public Integer call() throws Exception {
            List<Path> files = new ArrayList<>();
            for (Path p : paths) files.addAll(Discover.files(p));
            List<ParsedUnit> units = PlSqlParserFacade.parseFiles(files, Check.flags(defines), new int[]{19, 0});
            StructureModel m = StructureBuilder.build(units);
            Outline.logic = logic;
            Outline.print(m, System.out, verbose || logic);
            if (json != null) {
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), m);
                System.out.println("\nwrote " + json);
            }
            return m.issues.stream().anyMatch(i -> i.severity().equals("ERROR")) ? 1 : 0;
        }
    }

    /** M5 command: write the .agentdocs/ folder. */
    @Command(name = "build", description = "Analyse PL/SQL and write agent-ready documentation (.agentdocs/).",
            mixinStandardHelpOptions = true)
    static class BuildCmd implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "PL/SQL files and/or folders (default output goes next to the first folder)") List<Path> paths;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false")
        List<String> defines = new ArrayList<>();
        @Option(names = {"-o", "--out"}, description = "output folder (default: <first input folder>/.agentdocs)") Path out;
        @Option(names = "--no-source", description = "do not copy the original files into source/") boolean noSource;
        @Option(names = "--open", description = "after building, open the live Explorer UI") boolean open;

        @Override
        public Integer call() throws Exception {
            System.out.println(BANNER);
            Path first = paths.get(0).toAbsolutePath().normalize();
            Path root = java.nio.file.Files.isDirectory(first) ? first : first.getParent();
            Path outDir = out != null ? out : root.resolve(".agentdocs");
            long t0 = System.nanoTime();
            dev.sandeep.plsqlparser.run.Pipeline.Outcome o;
            try {
                o = dev.sandeep.plsqlparser.run.Pipeline.run(root, paths, Check.flags(defines), outDir, !noSource, dev.sandeep.plsqlparser.run.Progress.console(System.out));
            } catch (java.io.IOException e) {
                System.err.println(e.getMessage());
                return 1;
            }
            var r = o.docs();
            System.out.printf("%nwrote %s in %.1fs%n  %d files | %d packages | %d cards | %d tables | %d risks%n",
                    r.outDir(), (System.nanoTime() - t0) / 1e9, r.sourceFiles(), r.packages(), r.cards(), r.tables(), o.model().risks.size());
            printVerdict(System.out, r.verdict());
            System.out.println("\nstart with " + r.outDir().resolve("AGENTS.md"));
            if (open) {
                ExploreCmd e = new ExploreCmd();
                e.folder = r.outDir();
                e.call();
            }
            return o.verdict().equals("NOT_VERIFIED") ? 1 : 0;
        }
    }

    /** M6 command: serve the live Explorer UI for an .agentdocs folder. */
    @Command(name = "explore", description = "Open the Explorer UI (graph, outline, source, prompt packs) on a built .agentdocs folder.",
            mixinStandardHelpOptions = true)
    static class ExploreCmd implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".", description = "the .agentdocs folder, or the folder that contains it") Path folder;
        @Option(names = "--port", defaultValue = "9000", description = "first port to try on 127.0.0.1; the next 99 are tried if it is busy (default: 9000)") int port;
        @Option(names = "--no-open", description = "do not open the browser") boolean noOpen;

        @Override
        public Integer call() throws Exception {
            Path docs = java.nio.file.Files.exists(folder.resolve("INDEX.json")) ? folder : folder.resolve(".agentdocs");
            try (var server = dev.sandeep.plsqlparser.explore.ExplorerServer.startOnFirstFreePort(docs, port, 100)) {
                System.out.println("Explorer for " + docs.toAbsolutePath().normalize() + "\n  " + server.url() + "   (Ctrl+C to stop; re-run `build` and the page refreshes itself)");
                if (!noOpen && java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(server.url()));
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return 0;
        }
    }

    /** M4 command: risk report. */
    @Command(name = "risks", description = "List Oracle-specific hazards (swallowed errors, dynamic SQL, row-by-row DML, ...).",
            mixinStandardHelpOptions = true)
    static class RisksCmd implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "PL/SQL files and/or folders") List<Path> paths;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false")
        List<String> defines = new ArrayList<>();
        @Option(names = "--min", description = "minimum severity: HIGH, MEDIUM, LOW, INFO (default LOW)", defaultValue = "LOW") String min;
        @Option(names = {"-o", "--json"}, description = "write all risks to this JSON file") Path json;

        @Override
        public Integer call() throws Exception {
            List<Path> files = new ArrayList<>();
            for (Path p : paths) files.addAll(Discover.files(p));
            StructureModel m = StructureBuilder.build(PlSqlParserFacade.parseFiles(files, Check.flags(defines), new int[]{19, 0}));
            List<String> order = List.of("HIGH", "MEDIUM", "LOW", "INFO");
            int cut = order.indexOf(min.toUpperCase());
            Map<String, Integer> byCode = new TreeMap<>();
            for (Risk r : m.risks) byCode.merge(r.severity() + " " + r.code(), 1, Integer::sum);
            System.out.println("risk counts: " + byCode);
            for (Risk r : m.risks) {
                if (order.indexOf(r.severity()) > cut) continue;
                System.out.printf("[%s] %-28s %s  %s:%d%n      %s%n", r.severity(), r.code(), r.routineId(),
                        r.file().substring(r.file().lastIndexOf('/') + 1), r.line(), r.message());
            }
            if (json != null) {
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), m.risks);
                System.out.println("wrote " + json);
            }
            return m.risks.stream().anyMatch(r -> r.severity().equals("HIGH")) ? 2 : 0;
        }
    }

    /** M3 command: dependency graph summary, JSON and Mermaid export, impact queries. */
    @Command(name = "graph", description = "Build the call/table dependency graph; print a summary, export JSON/Mermaid.",
            mixinStandardHelpOptions = true)
    static class GraphCmd implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "PL/SQL files and/or folders") List<Path> paths;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false")
        List<String> defines = new ArrayList<>();
        @Option(names = {"-o", "--json"}, description = "write graph (nodes, edges, analysis) to this JSON file") Path json;
        @Option(names = "--mermaid", description = "write a package-level Mermaid diagram to this file") Path mermaid;
        @Option(names = "--table", description = "print readers/writers of this table (e.g. LOGGER_LOGS)") String table;
        @Option(names = "--routine", description = "print callees (transitive) of this routine id (e.g. LOGGER.LOG_ERROR)") String routine;

        @Override
        public Integer call() throws Exception {
            List<Path> files = new ArrayList<>();
            for (Path p : paths) files.addAll(Discover.files(p));
            StructureModel m = StructureBuilder.build(PlSqlParserFacade.parseFiles(files, Check.flags(defines), new int[]{19, 0}));
            DependencyGraph g = DependencyGraph.build(m);
            long calls = g.edges().stream().filter(e -> e.type.equals("CALLS")).count();
            System.out.printf("nodes=%d  edges=%d (calls=%d)  tables=%d%n", g.nodes.size(), g.edges().size(), calls, g.tables().size());
            var rec = g.recursionGroups();
            System.out.println("recursion groups: " + (rec.isEmpty() ? "none" : rec));
            System.out.println("uncalled private routines (dead-code candidates): " + g.uncalledPrivateRoutines());
            System.out.println("uncalled public routines (API entry points?): " + g.uncalledPublicRoutines().size());
            System.out.println("tables: " + g.tables());
            if (table != null) {
                var i = g.impactOf(table);
                System.out.printf("%nTable %s%n  readers: %s%n  writers: %s%n  reach a writer via calls: %s%n", i.table(), i.readers(), i.writers(), i.transitiveWriters());
            }
            if (routine != null) System.out.printf("%n%s calls (transitively): %s%n", routine.toUpperCase(), g.reachableFrom(routine.toUpperCase()));
            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            if (json != null) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("nodes", g.nodes.values());
                doc.put("edges", g.edges());
                doc.put("recursionGroups", rec);
                doc.put("uncalledPrivateRoutines", g.uncalledPrivateRoutines());
                doc.put("uncalledPublicRoutines", g.uncalledPublicRoutines());
                Map<String, Object> impact = new TreeMap<>();
                for (String t : g.tables()) impact.put(t, g.impactOf(t));
                doc.put("tableImpact", impact);
                om.writeValue(json.toFile(), doc);
                System.out.println("wrote " + json);
            }
            if (mermaid != null) {
                java.nio.file.Files.writeString(mermaid, MermaidWriter.packageDiagram(g));
                System.out.println("wrote " + mermaid);
            }
            return 0;
        }
    }

    /** M1 command: parse everything and report syntax errors / $IF variants. */
    @Command(name = "check", description = "Parse files and report syntax errors and conditional-compilation variants.",
            mixinStandardHelpOptions = true)
    static class Check implements Callable<Integer> {
        @Parameters(index = "0", description = "PL/SQL file or folder") Path path;
        @Option(names = {"-D", "--define"}, description = "conditional-compilation flag, e.g. -D no_op=false")
        List<String> defines = new ArrayList<>();
        @Option(names = "--max-errors", defaultValue = "5") int maxErrors;

        static Map<String, Object> flags(List<String> defines) {
            Map<String, Object> flags = new HashMap<>();
            for (String d : defines) {
                String[] kv = d.split("=", 2);
                String v = kv.length > 1 ? kv[1] : "true";
                flags.put(kv[0].toLowerCase(), v.equalsIgnoreCase("true") ? Boolean.TRUE
                        : v.equalsIgnoreCase("false") ? Boolean.FALSE : v);
            }
            return flags;
        }

        @Override
        public Integer call() throws Exception {
            Map<String, Object> flags = flags(defines);
            List<Path> files = Discover.files(path);
            long t0 = System.nanoTime();
            List<ParsedUnit> units = PlSqlParserFacade.parseFiles(files, flags, new int[]{19, 0});
            int bad = 0;
            for (ParsedUnit u : units) {
                long undecided = u.conditions().stream().filter(c -> !c.decided()).count();
                System.out.printf("%s %s [%s] lines=%d errors=%d undecided-$IF=%d%n", u.ok() ? "OK " : "ERR",
                        u.path(), u.variant(), u.lines(), u.errors().size(), undecided);
                u.errors().stream().limit(maxErrors).forEach(e ->
                        System.out.printf("     %d:%d %s%n", e.line(), e.column(), e.message()));
                if (!u.ok()) bad++;
            }
            System.out.printf("%d files / %d variants parsed in %.1fs, %d with syntax errors%n",
                    files.size(), units.size(), (System.nanoTime() - t0) / 1e9, bad);
            return bad == 0 ? 0 : 1;
        }
    }
}
