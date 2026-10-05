package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.CallSite;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * M3: finds call sites (procedure-call statements and function calls inside expressions). Candidate names are
 * filtered against declared variables / types so collection access, record fields and columns are not reported
 * as calls; the survivors are resolved later by {@link CallResolver}.
 */
final class CallExtractor {
    private final Set<String> vars;
    private final Registry reg;
    final List<CallSite> out = new ArrayList<>();

    CallExtractor(Set<String> scopeVariables, Registry registry) {
        this.vars = scopeVariables;
        this.reg = registry;
    }

    void scan(ParseTree root) {
        walk(root, false);
    }

    private void walk(ParseTree n, boolean inLoop) {
        if (n instanceof Loop_statementContext l) {
            for (int i = 0; i < l.getChildCount(); i++) walk(l.getChild(i), inLoop || l.getChild(i) instanceof Seq_of_statementsContext);
            return;
        }
        if (n instanceof Call_statementContext c) {
            statement(c, inLoop);
            for (Function_argumentContext fa : c.function_argument()) walk(fa, inLoop);
            return;
        }
        if (n instanceof General_elementContext g && !(g.getParent() instanceof General_elementContext)) {
            chain(g, inLoop);
            for (General_element_partContext p : Src.parts(g)) for (Function_argumentContext fa : p.function_argument()) walk(fa, inLoop);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), inLoop);
    }

    private void statement(Call_statementContext c, boolean inLoop) {
        if (c.routine_name().isEmpty()) return;
        Routine_nameContext rn = c.routine_name(0);
        String callee = Src.norm(Src.text(rn)).replaceAll("\\s*\\.\\s*", ".");
        String first = Src.ident(callee.split("[.@]")[0]);
        if (vars.contains(first)) return;                       // method on a collection / object variable
        String last = Src.ident(callee.substring(callee.lastIndexOf('.') + 1));
        if (callee.contains(".") && OracleBuiltins.isCollectionMethod(last) && vars.contains(first)) return;
        Function_argumentContext fa = c.function_argument().isEmpty() ? null : c.function_argument(0);
        if (fa != null && c.routine_name().size() > 1 && fa.getStart().getStartIndex() > c.routine_name(1).getStart().getStartIndex()) fa = null;
        CallSite s = site(callee, c, fa, inLoop);
        s.statement = true;
        s.parens = fa != null;
        out.add(s);
    }

    private void chain(General_elementContext g, boolean inLoop) {
        List<General_element_partContext> parts = Src.parts(g);
        if (parts.isEmpty()) return;
        int k = -1;
        for (int i = 0; i < parts.size(); i++) if (!parts.get(i).function_argument().isEmpty()) { k = i; break; }
        int upto = k >= 0 ? k + 1 : parts.size();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < upto; i++) names.add(Src.norm(Src.text(parts.get(i).id_expression())));
        String first = Src.ident(names.get(0));
        if (vars.contains(first)) return;                       // collection element / record field / object method
        boolean parens = k >= 0;
        if (names.size() == 1) {
            if (parens ? (reg.typeNames.contains(first) || OracleBuiltins.isCollectionMethod(first) && vars.contains(first))
                    : !reg.routineNames.contains(first)) return;
        } else {
            String second = Src.ident(names.get(1));
            boolean relevant = parens || reg.packages.containsKey(first) || OracleBuiltins.isPackage(first)
                    || names.size() > 2 && reg.packages.containsKey(second);
            if (!relevant) return;
        }
        Function_argumentContext fa = k >= 0 ? parts.get(k).function_argument(0) : null;
        CallSite s = site(String.join(".", names), g, fa, inLoop);
        s.parens = parens;
        out.add(s);
    }

    private CallSite site(String callee, ParserRuleContext ctx, Function_argumentContext fa, boolean inLoop) {
        CallSite s = new CallSite();
        s.callee = callee;
        s.line = ctx.getStart().getLine();
        s.offset = ctx.getStart().getStartIndex();
        s.inLoop = inLoop;
        s.text = Src.clip(Src.norm(Src.text(ctx)), 200);
        if (fa != null) {
            s.argCount = fa.argument().size();
            for (ArgumentContext a : fa.argument()) if (a.identifier() != null) s.namedArgs.add(Src.text(a.identifier()));
        }
        return s;
    }
}
