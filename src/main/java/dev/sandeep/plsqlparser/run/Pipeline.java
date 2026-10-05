package dev.sandeep.plsqlparser.run;

import dev.sandeep.plsqlparser.emit.AgentDocs;
import dev.sandeep.plsqlparser.extract.StructureBuilder;
import dev.sandeep.plsqlparser.model.StructureModel;
import dev.sandeep.plsqlparser.parse.Discover;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The whole job in one call: find files, parse, analyse across files, write {@code .agentdocs/}, verify. Used by {@code build} and {@code go}. */
public final class Pipeline {
    public record Outcome(StructureModel model, AgentDocs.Result docs) {
        public String verdict() { return docs.verdict().verdict(); }
    }

    private Pipeline() {}

    /** @param inputs files and/or folders to scan (relative paths in the docs are relative to {@code root}) */
    public static Outcome run(Path root, List<Path> inputs, Map<String, Object> flags, Path out, boolean copySource, Progress p) throws IOException {
        try {
            Path base = root.toAbsolutePath().normalize();
            p.stage(Progress.DISCOVER, "Looking for PL/SQL files in " + base);
            List<Path> files = new ArrayList<>();
            for (Path in : inputs) files.addAll(Discover.files(in));
            files = files.stream().map(f -> f.toAbsolutePath().normalize()).distinct().sorted().toList();
            if (files.isEmpty()) throw new IOException("no PL/SQL files (" + String.join(", ", Discover.EXTENSIONS.stream().sorted().map(e -> "." + e).toList()) + ") found under " + base);
            p.log("found " + files.size() + " file(s)");
            for (Path f : files) p.log(base.relativize(f).toString().replace('\\', '/'));

            p.stage(Progress.PARSE, "Parsing " + files.size() + " file(s) with the Oracle PL/SQL grammar (conditional compilation resolved first)");
            AtomicInteger done = new AtomicInteger();
            int total = files.size();
            var parsed = PlSqlParserFacade.parseFiles(files, base, flags, new int[]{19, 0},
                    (file, errors) -> p.file(file, done.incrementAndGet(), total, errors));

            p.stage(Progress.ANALYSE, "Linking specs and bodies, extracting SQL, resolving calls across files, building logic outlines and risks");
            StructureModel m = StructureBuilder.build(parsed);
            p.log(m.units.size() + " unit(s), " + m.units.stream().mapToInt(u -> u.decls.routines.size()).sum() + " top-level routine(s), " + m.risks.size() + " risk(s)");

            AgentDocs.Result r = AgentDocs.write(m, base, out, copySource, p);
            for (var c : r.verdict().checks()) p.log(String.format("%-4s %s — %s", c.status(), c.title(), c.evidence()));
            p.finished(r.verdict().verdict(), r.verdict().summary());
            return new Outcome(m, r);
        } catch (IOException | RuntimeException e) {
            p.finished(null, String.valueOf(e.getMessage() == null ? e : e.getMessage()));
            throw e;
        }
    }

    /** Cheap change detector for {@code --watch}: names, sizes and modification times of the PL/SQL files. */
    public static String fingerprint(Path root) {
        try {
            StringBuilder b = new StringBuilder();
            for (Path f : Discover.files(root)) b.append(f).append('|').append(Files.size(f)).append('|').append(Files.getLastModifiedTime(f).toMillis()).append('\n');
            return b.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
