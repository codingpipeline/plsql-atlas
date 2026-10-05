package dev.sandeep.plsqlparser.parse;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Finds PL/SQL source files under a path (file or folder). */
public final class Discover {
    public static final Set<String> EXTENSIONS =
            Set.of("sql", "pks", "pkb", "pkg", "fnc", "prc", "trg", "vw", "typ", "tps", "tpb", "plsql");
    private static final Set<String> SKIP_DIRS = Set.of(".agentdocs", ".git", "node_modules", "target", "corpus-out");

    private Discover() {}

    public static List<Path> files(Path root) throws IOException {
        if (Files.isRegularFile(root)) return List.of(root);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> EXTENSIONS.contains(ext(p)))
                    .filter(p -> StreamSupport.stream(root.relativize(p).spliterator(), false)
                            .noneMatch(seg -> SKIP_DIRS.contains(seg.toString())))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
