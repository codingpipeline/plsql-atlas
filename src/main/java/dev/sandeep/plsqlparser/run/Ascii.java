package dev.sandeep.plsqlparser.run;

/**
 * Console output must survive any terminal code page (a default Windows console shows an em dash as "?" or a box).
 * Typographic symbols are therefore replaced by plain ASCII at the console boundary only; the web page and the
 * written documents keep their original characters.
 */
public final class Ascii {
    private Ascii() {}

    /** Replaces typographic symbols with ASCII equivalents; other characters (letters in file names) are left alone. */
    public static String clean(String s) {
        if (s == null) return null;
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = c < 128 ? null : replacement(c);
            if (r != null && b == null) b = new StringBuilder(s.length() + 8).append(s, 0, i);
            if (b != null) b.append(r != null ? r : String.valueOf(c));
        }
        return b == null ? s : b.toString();
    }

    private static String replacement(char c) {
        return switch (c) {
            case '—', '–', '−' -> "-";          // em dash, en dash, minus
            case '→' -> "->";
            case '←' -> "<-";
            case '↔' -> "<->";
            case '…' -> "...";
            case '·', '•' -> "-";                    // middle dot, bullet
            case '×' -> "x";
            case '≥' -> ">=";
            case '≤' -> "<=";
            case '“', '”' -> "\"";
            case '‘', '’' -> "'";
            case '✓' -> "ok";
            case '✗' -> "x";
            case '⚠' -> "!";
            case ' ' -> " ";
            default -> null;
        };
    }
}
