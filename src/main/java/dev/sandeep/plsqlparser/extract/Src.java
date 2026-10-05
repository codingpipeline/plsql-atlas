package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.function.Consumer;

/** Small helpers for reading source text and walking parse trees. */
final class Src {
    private Src() {}

    static String text(ParserRuleContext c) {
        if (c == null || c.getStart() == null || c.getStop() == null || c.getStop().getStopIndex() < c.getStart().getStartIndex()) return "";
        return c.getStart().getInputStream().getText(Interval.of(c.getStart().getStartIndex(), c.getStop().getStopIndex()));
    }

    static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static int endLine(ParserRuleContext c) {
        if (c == null || c.getStart() == null) return 0;   // holes left by syntax-error recovery
        Token s = c.getStop();
        if (s == null) return c.getStart().getLine();
        int n = 0;
        String t = s.getText();
        for (int i = 0; t != null && i < t.length(); i++) if (t.charAt(i) == '\n') n++;
        return s.getLine() + n;
    }

    static String rule(ParserRuleContext c) {
        return PlSqlParser.ruleNames[c.getRuleIndex()];
    }

    /** Lower-case identifier text with surrounding double quotes removed. */
    static String ident(String s) {
        return s == null ? "" : s.strip().replace("\"", "").toLowerCase();
    }

    /** Depth-first visit of every node under (and including) root. */
    static void each(ParseTree root, Consumer<ParseTree> f) {
        f.accept(root);
        for (int i = 0; i < root.getChildCount(); i++) each(root.getChild(i), f);
    }

    static boolean hasAncestor(ParseTree n, Class<? extends ParserRuleContext> type) {
        for (ParseTree p = n.getParent(); p != null; p = p.getParent()) if (type.isInstance(p)) return true;
        return false;
    }

    /** Flatten a (left-recursive) general_element chain a.b(x).c into its parts in order. */
    static java.util.List<PlSqlParser.General_element_partContext> parts(PlSqlParser.General_elementContext g) {
        java.util.List<PlSqlParser.General_element_partContext> out = new java.util.ArrayList<>();
        if (g.general_element() != null) out.addAll(parts(g.general_element()));
        out.addAll(g.general_element_part());
        return out;
    }

    static boolean isTerminal(ParseTree t) {
        return t instanceof TerminalNode;
    }
}
