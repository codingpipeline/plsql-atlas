package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/** M4: shared-state, sequence, DDL, lock, DB-link and external-I/O effects of one body. */
final class SideEffectAnalyzer {
    private static final Set<String> DDL_VERBS = Set.of("TRUNCATE", "DROP", "ALTER", "CREATE", "COMMENT", "GRANT", "REVOKE", "ANALYZE", "RENAME");

    private SideEffectAnalyzer() {}

    static void analyze(List<SqlStatement> sql, List<CallSite> calls, List<? extends ParseTree> roots, Set<String> stateVars,
                        Set<String> shadow, SideEffects fx) {
        for (SqlStatement s : sql) {
            if (s.dynamic) fx.dynamicSqlLines.add(s.line);
            boolean ddl = s.dynamic && s.dynamicKind != null && DDL_VERBS.contains(s.dynamicKind.toUpperCase())
                    || s.tables.stream().anyMatch(t -> t.access.contains("DDL"));
            String tables = tables(s);
            if (ddl) fx.ddl.add((s.dynamicKind != null ? s.dynamicKind : "DDL") + tables + " (dynamic, line " + s.line + ")");
            if (s.forUpdate) fx.locks.add("SELECT FOR UPDATE" + tables + " (line " + s.line + ")");
            if (s.kind.equals("LOCK")) fx.locks.add("LOCK TABLE" + tables + " (line " + s.line + ")");
            for (TableRef t : s.tables) if (t.dbLink != null) fx.dbLinks.add(t.dbLink + " via " + t.key());
        }
        for (CallSite c : calls) {
            if (c.callee.contains("@")) fx.dbLinks.add(c.callee);
            if (CallSite.BUILTIN.equals(c.resolution) && c.builtinPackage != null && !c.builtinPackage.equals("STANDARD"))
                fx.externalIo.computeIfAbsent(OracleBuiltins.category(c.builtinPackage), k -> new TreeSet<>()).add(c.callee.toUpperCase());
        }
        for (ParseTree root : roots) walk(root, stateVars, shadow, fx);
    }

    private static String tables(SqlStatement s) {
        if (s.tables.isEmpty()) return "";
        StringBuilder b = new StringBuilder(" ");
        for (int i = 0; i < s.tables.size(); i++) b.append(i > 0 ? "," : "").append(s.tables.get(i).key());
        return b.toString();
    }

    private static void walk(ParseTree root, Set<String> stateVars, Set<String> shadow, SideEffects fx) {
        Src.each(root, n -> {
            if (n instanceof General_elementContext g && !(g.getParent() instanceof General_elementContext)) {
                List<General_element_partContext> parts = Src.parts(g);
                if (parts.isEmpty()) return;
                List<String> names = new ArrayList<>();
                for (General_element_partContext p : parts) names.add(Src.ident(Src.text(p.id_expression())));
                String last = names.get(names.size() - 1);
                if (names.size() >= 2 && (last.equals("nextval") || last.equals("currval")))
                    fx.sequences.add(String.join(".", names.subList(0, names.size() - 1)).toUpperCase());
                String first = names.get(0);
                if (stateVars.contains(first) && !shadow.contains(first)) {
                    boolean write = g.getParent() instanceof Assignment_statementContext a && a.general_element() == g
                            || Src.hasAncestor(g, Into_clauseContext.class);
                    (write ? fx.packageStateWrites : fx.packageStateReads).add(first);
                }
            } else if (n instanceof Fetch_statementContext f) {
                for (Variable_or_collectionContext v : f.variable_or_collection()) {
                    String first = Src.ident(Src.text(v)).split("[.(]")[0];
                    if (stateVars.contains(first) && !shadow.contains(first)) fx.packageStateWrites.add(first);
                }
            }
        });
    }
}
