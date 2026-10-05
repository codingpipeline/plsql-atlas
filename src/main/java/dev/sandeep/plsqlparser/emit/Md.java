package dev.sandeep.plsqlparser.emit;

import java.nio.file.Path;
import java.util.*;

/** Markdown and path helpers shared by all emitters. */
final class Md {
    private Md() {}

    static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    static String code(String s) {
        return s == null || s.isEmpty() ? "" : "`" + s.replace("`", "'") + "`";
    }

    static String fence(String lang, String body) {
        String fence = body.contains("```") ? "~~~~" : "```";
        return fence + lang + "\n" + body + (body.endsWith("\n") ? "" : "\n") + fence + "\n";
    }

    static String table(List<String> header, List<List<String>> rows) {
        StringBuilder b = new StringBuilder("| ").append(String.join(" | ", header)).append(" |\n|");
        for (int i = 0; i < header.size(); i++) b.append("---|");
        b.append('\n');
        for (List<String> r : rows) {
            b.append("| ");
            for (int i = 0; i < r.size(); i++) b.append(i > 0 ? " | " : "").append(esc(r.get(i)));
            b.append(" |\n");
        }
        return b.toString();
    }

    /** Relative link from one generated file to another (both relative to the output root). */
    static String link(String fromFile, String toFile) {
        Path from = Path.of(fromFile).getParent();
        Path to = Path.of(toFile);
        String rel = (from == null ? to : from.relativize(to)).toString().replace('\\', '/');
        return rel;
    }

    static String mdLink(String text, String fromFile, String toFile) {
        return "[" + text.replace("[", "(").replace("]", ")") + "](" + link(fromFile, toFile) + ")";
    }

    static String list(Collection<String> items) {
        if (items.isEmpty()) return "_none_";
        StringBuilder b = new StringBuilder();
        for (String i : items) b.append("- ").append(i).append('\n');
        return b.toString();
    }

    /** File-system safe, lower-case slug. */
    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replace(">", "__").replace("#", "_").replace("~", "_").replaceAll("[^a-z0-9_.$-]", "_");
    }

    static String fileName(String f) {
        return f.substring(f.lastIndexOf('/') + 1);
    }
}
