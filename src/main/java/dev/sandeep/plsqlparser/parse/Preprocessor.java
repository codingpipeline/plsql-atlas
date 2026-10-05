package dev.sandeep.plsqlparser.parse;

import java.util.*;
import java.util.regex.*;

/**
 * Conditional-compilation pre-pass ($IF / $ELSIF / $ELSE / $END / $ERROR).
 *
 * The ANTLR grammar cannot parse these directives, so each file is resolved into one or more variants
 * before parsing. Excluded text and directives are blanked with spaces; newlines are kept, so line
 * numbers in every variant equal line numbers in the original file.
 *
 * Variants: "primary" (an undefined $$flag is NULL, as in Oracle: conditions on it are not true) and, only when some
 * condition was undecidable, "alternate" (undecidable -> TRUE). Nothing is silently dropped.
 */
public final class Preprocessor {
    public record Condition(int line, String text, boolean decided, Boolean value) {}
    public record Variant(String name, String text, List<Condition> conditions) {}

    private static final Pattern DIR = Pattern.compile("\\$(if|elsif|else|end|error)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THEN = Pattern.compile("\\$then\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOK = Pattern.compile(
            "\\s*(\\$\\$\\w+|[A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)*|\\d+(?:\\.\\d+)?|<=|>=|!=|<>|=|<|>|\\(|\\)|'[^']*')");
    private static final Pattern VER = Pattern.compile("ver_le_(\\d+)(?:_(\\d+))?");

    private Preprocessor() {}

    /** Same-length copy with comment/string contents replaced by spaces (newlines kept). */
    public static String mask(String t) {
        char[] out = t.toCharArray();
        int n = t.length(), i = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '-' && t.startsWith("--", i)) {
                int j = t.indexOf('\n', i); if (j < 0) j = n;
                blank(out, i, j); i = j;
            } else if (c == '/' && t.startsWith("/*", i)) {
                int j = t.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                blank(out, i, j); i = j;
            } else if ((c == 'q' || c == 'Q') && i + 2 < n && t.charAt(i + 1) == '\'' && !(i > 0 && isIdent(t.charAt(i - 1)))) {
                char d = t.charAt(i + 2);
                char close = d == '[' ? ']' : d == '(' ? ')' : d == '{' ? '}' : d == '<' ? '>' : d;
                int j = t.indexOf("" + close + '\'', i + 3); j = j < 0 ? n : j + 2;
                blank(out, i, j); i = j;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (t.charAt(j) == '\'') {
                        if (j + 1 < n && t.charAt(j + 1) == '\'') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                blank(out, i + 1, Math.min(j, n)); i = j + 1;
            } else i++;
        }
        return new String(out);
    }

    private static boolean isIdent(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#'; }

    private static void blank(char[] a, int from, int to) {
        for (int k = from; k < to && k < a.length; k++) if (a[k] != '\n' && a[k] != '\r') a[k] = ' ';
    }

    public static List<Variant> preprocess(String text, Map<String, Object> flags, int[] target) {
        Map<String, Object> fl = new HashMap<>();
        if (flags != null) flags.forEach((k, v) -> fl.put(k.toLowerCase(), v));
        String masked = mask(text);
        if (!DIR.matcher(masked).find()) return List.of(new Variant("primary", text, List.of()));
        List<Variant> out = new ArrayList<>();
        String[] names = {"primary", "alternate"};
        Boolean[] unknownAs = {null, Boolean.TRUE}; // Oracle: an undefined $$flag is NULL; alternate pretends it is set
        for (int vi = 0; vi < 2; vi++) {
            List<Condition> conds = new ArrayList<>();
            boolean anyUndefined = false;
            Deque<boolean[]> stack = new ArrayDeque<>(); // [parentActive, taken, thisActive]
            boolean active = true;
            List<int[]> segs = new ArrayList<>();         // {start, end, active?1:0}
            int segStart = 0, pos = 0;
            while (true) {
                Matcher m = DIR.matcher(masked);
                if (!m.find(pos)) break;
                String kind = m.group(1).toLowerCase();
                segs.add(new int[]{segStart, m.start(), active ? 1 : 0});
                int end = m.end();
                if (kind.equals("if") || kind.equals("elsif")) {
                    Matcher tm = THEN.matcher(masked);
                    boolean hasThen = tm.find(end);
                    int cend = hasThen ? tm.start() : end;
                    String cond = text.substring(end, cend);
                    if (hasThen) end = tm.end();
                    Eval ev = eval(cond, fl, target, unknownAs[vi]);
                    Boolean v = ev.value();
                    boolean val = v != null && v;     // NULL counts as false, as in Oracle
                    anyUndefined |= ev.usedUnknown();
                    conds.add(new Condition(lineOf(text, m.start()), cond.trim().replaceAll("\\s+", " "), !ev.usedUnknown(), v));
                    if (kind.equals("if")) {
                        stack.push(new boolean[]{active, val && active, val && active});
                        active = stack.peek()[2];
                    } else {
                        boolean[] fr = stack.peek();
                        boolean take = !fr[1] && val && fr[0];
                        fr[1] = fr[1] || take; fr[2] = take; active = take;
                    }
                } else if (kind.equals("else")) {
                    boolean[] fr = stack.peek();
                    boolean take = !fr[1] && fr[0];
                    fr[1] = true; fr[2] = take; active = take;
                } else if (kind.equals("end")) {
                    boolean[] fr = stack.isEmpty() ? new boolean[]{true, true, true} : stack.pop();
                    active = fr[0];
                } else { // $error <message> $end : message is not code
                    Matcher e = DIR.matcher(masked);
                    int stop = e.find(end) ? e.start() : end;
                    conds.add(new Condition(lineOf(text, m.start()),
                            "$error " + text.substring(m.end(), stop).trim().replaceAll("\\s+", " "), true, null));
                    end = stop;
                }
                segs.add(new int[]{m.start(), end, 0});
                segStart = end; pos = end;
            }
            segs.add(new int[]{segStart, text.length(), active ? 1 : 0});
            char[] keep = text.toCharArray();
            for (int[] s : segs) if (s[2] == 0) blank(keep, s[0], s[1]);
            out.add(new Variant(names[vi], new String(keep), conds));
            if (!anyUndefined) break; // nothing depended on an undefined flag: one variant is the whole truth
        }
        return out;
    }

    private static int lineOf(String t, int idx) {
        int n = 1;
        for (int i = 0; i < idx; i++) if (t.charAt(i) == '\n') n++;
        return n;
    }

    // ---- expression evaluator: three-valued (true/false/unknown=null) ----
    private static final class Ev {
        final List<String> toks; int i = 0; final Map<String, Object> flags; final int[] target; final Boolean unknownAs; boolean usedUnknown;
        Ev(List<String> toks, Map<String, Object> flags, int[] target, Boolean unknownAs) { this.toks = toks; this.flags = flags; this.target = target; this.unknownAs = unknownAs; }
        String peek() { return i < toks.size() ? toks.get(i).toLowerCase() : null; }

        Object atom() {
            String t = peek();
            if (t == null) throw new IllegalStateException("eof");
            if (t.equals("(")) { i++; Object v = or(); i++; return v; }
            if (t.equals("not")) { i++; Object v = atom(); return v instanceof Boolean b ? (Object) (!b) : null; }
            i++;
            if (t.equals("true")) return Boolean.TRUE;
            if (t.equals("false")) return Boolean.FALSE;
            if (t.equals("null")) return null;
            if (t.matches("\\d+(\\.\\d+)?")) return Double.parseDouble(t);
            if (t.startsWith("'")) return t.substring(1, t.length() - 1);
            if (t.startsWith("dbms_db_version.")) {
                String k = t.substring("dbms_db_version.".length());
                Matcher m = VER.matcher(k);
                if (m.matches()) {
                    int a = Integer.parseInt(m.group(1)), b = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                    return target[0] < a || (target[0] == a && target[1] <= b);
                }
                if (k.equals("version")) return (double) target[0];
                if (k.equals("release")) return (double) target[1];
                return null;
            }
            String key = t.startsWith("$$") ? t.substring(2) : t;
            if (flags.containsKey(key)) return flags.get(key);
            usedUnknown = true;
            return unknownAs;
        }

        Object cmp() {
            Object a = atom();
            String p = peek();
            if ("is".equals(p)) {
                i++;
                boolean neg = "not".equals(peek());
                if (neg) i++;
                i++; // NULL
                return neg ? a != null : a == null;
            }
            if (p != null && List.of("=", "<", ">", "<=", ">=", "!=", "<>").contains(p)) {
                i++;
                Object b = atom();
                if (a == null || b == null) return null;
                int c = (a instanceof Double x && b instanceof Double y) ? Double.compare(x, y) : (a.equals(b) ? 0 : 1);
                return switch (p) { case "=" -> c == 0; case "<" -> c < 0; case ">" -> c > 0; case "<=" -> c <= 0; case ">=" -> c >= 0; default -> c != 0; };
            }
            return a;
        }

        Object and() {
            Object v = cmp();
            while ("and".equals(peek())) {
                i++;
                Object r = cmp();
                v = (Boolean.FALSE.equals(v) || Boolean.FALSE.equals(r)) ? Boolean.FALSE
                        : (v == null || r == null ? null : Boolean.TRUE);
            }
            return v;
        }

        Object or() {
            Object v = and();
            while ("or".equals(peek())) {
                i++;
                Object r = and();
                v = (Boolean.TRUE.equals(v) || Boolean.TRUE.equals(r)) ? Boolean.TRUE
                        : (v == null || r == null ? null : Boolean.FALSE);
            }
            return v;
        }
    }

    record Eval(Boolean value, boolean usedUnknown) {}

    static Eval eval(String expr, Map<String, Object> flags, int[] target, Boolean unknownAs) {
        List<String> toks = new ArrayList<>();
        String e = expr.trim();
        int pos = 0;
        Matcher m = TOK.matcher(e);
        while (pos < e.length()) {
            m.region(pos, e.length());
            if (!m.lookingAt()) return new Eval(null, true);
            toks.add(m.group(1));
            pos = m.end();
        }
        Ev ev = new Ev(toks, flags, target, unknownAs);
        try {
            Object v = ev.or();
            return new Eval(v instanceof Boolean b ? b : null, ev.usedUnknown);
        } catch (RuntimeException ex) {
            return new Eval(null, true);
        }
    }
}
