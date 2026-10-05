package dev.sandeep.plsqlparser.emit;

import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.analyze.MigrationOrder;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Entry point of M5: turns a {@link StructureModel} into the {@code .agentdocs/} folder. */
public final class AgentDocs {
    public static final String VERSION = "0.1.0";

    public record Result(Path outDir, int cards, int packages, int tables, int unaccountedLines, int sourceFiles, Verifier.Report verdict) {}

    private AgentDocs() {}

    /**
     * @param model     analysed model (all risks etc. already attached)
     * @param root      folder the input paths are relative to (used for the {@code source/} copy layout)
     * @param out       output folder (created; a previous run's output is replaced)
     * @param copySource copy the original files into {@code source/}
     */
    public static Result write(StructureModel model, Path root, Path out, boolean copySource) throws IOException {
        return write(model, root, out, copySource, dev.sandeep.plsqlparser.run.Progress.NONE);
    }

    public static Result write(StructureModel model, Path root, Path out, boolean copySource, dev.sandeep.plsqlparser.run.Progress progress) throws IOException {
        progress.stage(dev.sandeep.plsqlparser.run.Progress.WRITE, "Writing routine cards, package and table pages, file map and reports to " + out);
        prepare(out);
        DependencyGraph graph = DependencyGraph.build(model);
        MigrationOrder.Order order = MigrationOrder.compute(graph);
        Map<String, String> texts = new HashMap<>();
        DocContext ctx = new DocContext(model, graph, order, f -> texts.computeIfAbsent(f, k -> {
            try {
                return PlSqlParserFacade.read(root.toAbsolutePath().normalize().resolve(k)).replace("\r\n", "\n").replace('\r', '\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }), VERSION);

        CardWriter cards = new CardWriter(ctx, out);
        for (Routine r : ctx.routineById.values()) cards.writeRoutine(r);
        for (PlsqlUnit u : ctx.unitCardPath.keySet()) cards.writeUnitBlock(u);

        PackageAndTableWriter pt = new PackageAndTableWriter(ctx, out);
        pt.writePackages();
        pt.writeTables();

        ReportWriter rep = new ReportWriter(ctx, out);
        rep.writeAgentsMd();
        rep.writeOverview();
        rep.writeIndex();
        rep.writeFileDependencies();
        ErBuilder.write(ctx, out, ErBuilder.build(ctx));
        rep.writeGraph();
        rep.writeMigrationOrder();
        rep.writeRisks();
        rep.writeCoverage();
        rep.writeConfidence();

        if (copySource) copySources(model, root, out.resolve("source"));
        int unaccounted = rep.coverage.stream().mapToInt(c -> c.unaccountedCount()).sum();
        progress.stage(dev.sandeep.plsqlparser.run.Progress.VERIFY, "Cross-checking the written folder against the input files");
        Verifier.Report verdict = new Verifier(ctx, rep, root, out, copySource).run();
        return new Result(out, ctx.cardPath.size() + ctx.unitCardPath.size(), ctx.pkgPath.size(), ctx.tablePath.size(), unaccounted,
                (int) model.files.stream().map(StructureModel.FileInfo::file).distinct().count(), verdict);
    }

    /** Replace a previous run's output; refuse to wipe a folder that does not look like ours. */
    private static void prepare(Path out) throws IOException {
        if (Files.exists(out)) {
            boolean ours = Files.exists(out.resolve("AGENTS.md")) && Files.exists(out.resolve("INDEX.json"));
            boolean empty;
            try (Stream<Path> s = Files.list(out)) { empty = s.findAny().isEmpty(); }
            if (!ours && !empty) throw new IOException(out + " exists and is not a previous .agentdocs output; refusing to overwrite it");
            if (ours) deleteTree(out);
        }
        Files.createDirectories(out);
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) if (!x.equals(p)) Files.delete(x);
        }
    }

    private static void copySources(StructureModel model, Path root, Path dest) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        for (String f : model.files.stream().map(StructureModel.FileInfo::file).distinct().sorted().toList()) {
            Path src = base.resolve(f).normalize();
            Path rel = src.startsWith(base) ? base.relativize(src) : src.getFileName();
            Path target = dest.resolve(rel.toString());
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
