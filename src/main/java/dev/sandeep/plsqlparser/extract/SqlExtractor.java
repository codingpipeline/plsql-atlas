package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlLexer;
import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.SqlStatement;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * M3: finds every SQL statement embedded in a body / declaration and records the tables it touches
 * (READ / INSERT / UPDATE / DELETE / MERGE / LOCK), columns where attributable, PL/SQL binds and loop context.
 * Dynamic SQL (EXECUTE IMMEDIATE, OPEN FOR expression) is analysed best-effort and flagged with its confidence.
 */
final class SqlExtractor {
    private static final Set<String> NOT_COLUMNS = Set.of("rownum", "rowid", "level", "sysdate", "systimestamp", "user", "null",
            "sqlcode", "sqlerrm", "true", "false", "connect_by_isleaf", "connect_by_iscycle");
    private static final Pattern DYN_TABLE = Pattern.compile(
            "(?i)\\b(insert\\s+into|merge\\s+into|delete\\s+from|update|from|join|truncate\\s+table|drop\\s+table|alter\\s+table|create\\s+table|create\\s+global\\s+temporary\\s+table|comment\\s+on\\s+table|lock\\s+table)\\s+([a-z_][\\w$#]*(?:\\.[a-z_][\\w$#]*)?)");
    private static final Set<String> DYN_STOP = Set.of("select", "dual", "table", "set", "where", "values", "only", "lateral");

    private final Set<String> vars;
    private final String context;
    final List<SqlStatement> out = new ArrayList<>();

    SqlExtractor(Set<String> scopeVariables, String context) {
        this.vars = scopeVariables;
        this.context = context;
    }

    void scan(ParseTree root) {
        walk(root, false);
    }

    // ------------------------------------------------------------------ discovery

    private void walk(ParseTree n, boolean inLoop) {
        if (n instanceof Loop_statementContext l) {
            for (int i = 0; i < l.getChildCount(); i++) walk(l.getChild(i), inLoop || l.getChild(i) instanceof Seq_of_statementsContext);
            return;
        }
        if (n instanceof Forall_statementContext f) {
            if (f.data_manipulation_language_statements() != null) dml(f.data_manipulation_language_statements(), inLoop, true);
            else if (f.execute_immediate() != null) dynamic(f.execute_immediate(), f.execute_immediate().expression(), "EXECUTE_IMMEDIATE", inLoop, true);
            return;
        }
        if (n instanceof Data_manipulation_language_statementsContext d) { dml(d, inLoop, false); return; }
        if (n instanceof Execute_immediateContext e) { dynamic(e, e.expression(), "EXECUTE_IMMEDIATE", inLoop, false); return; }
        if (n instanceof Open_for_statementContext o) {
            if (o.select_statement() != null) analyze(o.select_statement(), "OPEN_FOR", inLoop, false);
            else if (o.expression() != null) dynamic(o, o.expression(), "OPEN_FOR", inLoop, false);
            return;
        }
        if (n instanceof Cursor_loop_paramContext c && c.select_statement() != null) {
            analyze(c.select_statement(), "CURSOR_LOOP", inLoop, false);
            return;
        }
        if (n instanceof Cursor_declarationContext cd) {
            if (cd.select_statement() != null) analyze(cd.select_statement(), "CURSOR_DECL", inLoop, false);
            return;
        }
        if (n instanceof Select_statementContext s) { analyze(s, "SELECT", inLoop, false); return; }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), inLoop);
    }

    private void dml(Data_manipulation_language_statementsContext d, boolean inLoop, boolean bulk) {
        ParseTree c = d.getChild(0);
        String kind = c instanceof Merge_statementContext ? "MERGE"
                : c instanceof Lock_table_statementContext ? "LOCK"
                : c instanceof Select_statementContext ? "SELECT"
                : c instanceof Update_statementContext ? "UPDATE"
                : c instanceof Delete_statementContext ? "DELETE"
                : c instanceof Insert_statementContext ? "INSERT" : null;
        if (kind == null) return; // EXPLAIN PLAN etc.
        analyze((ParserRuleContext) c, kind, inLoop, bulk);
    }

    // ------------------------------------------------------------------ static statements

    private SqlStatement analyze(ParserRuleContext ctx, String kind, boolean inLoop, boolean bulk) {
        SqlStatement s = new SqlStatement();
        s.kind = kind;
        s.context = context;
        s.line = ctx.getStart().getLine();
        s.offset = ctx.getStart().getStartIndex();
        s.endLine = Src.endLine(ctx);
        s.text = Src.norm(Src.text(ctx));
        s.inLoop = inLoop;
        s.bulk = bulk;
        Set<String> cte = new HashSet<>(), aliases = new HashSet<>();
        Set<Tableview_nameContext> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        Src.each(ctx, n -> {
            if (n instanceof Subquery_factoring_clauseContext q) cte.add(Src.ident(Src.text(q.query_name())));
            else if (n instanceof Column_aliasContext a) aliases.add(Src.ident(Src.text(a)));
            else if (n instanceof For_update_clauseContext) s.forUpdate = true;
            else if (n instanceof Into_clauseContext ic) {
                if (ic.BULK() != null) s.bulk = true;
                for (ParseTree ch : ic.children) {
                    if (ch instanceof General_elementContext || ch instanceof Bind_variableContext)
                        s.intoTargets.add(Src.norm(Src.text((ParserRuleContext) ch)));
                }
            } else if (n instanceof Bind_variableContext b) s.binds.add(Src.norm(Src.text(b)));
        });
        collectTables(ctx, s, cte, handled);
        attributeColumns(ctx, s, aliases);
        detectJoins(s);
        out.add(s);
        return s;
    }

    private static final java.util.regex.Pattern EQUALITY = java.util.regex.Pattern.compile(
            "([a-z_][\\w$#]*)\\.([a-z_][\\w$#]*)\\s*(?:\\(\\+\\))?\\s*=\\s*([a-z_][\\w$#]*)\\.([a-z_][\\w$#]*)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** {@code a.x = b.y} between two tables of the statement (WHERE or JOIN ... ON) is recorded as a join: the raw material for inferred ER relationships. */
    private static void detectJoins(SqlStatement s) {
        if (s.tables.size() < 2 || s.text == null) return;
        var m = EQUALITY.matcher(s.text);
        while (m.find()) {
            TableRef a = byQualifier(s, m.group(1)), b = byQualifier(s, m.group(3));
            if (a == null || b == null || a == b || a.key().equals(b.key())) continue;
            var j = new SqlStatement.JoinRef(a.key(), m.group(2).toUpperCase(), b.key(), m.group(4).toUpperCase());
            if (!s.joins.contains(j)) s.joins.add(j);
        }
    }

    private static TableRef byQualifier(SqlStatement s, String q) {
        for (TableRef t : s.tables) if (t.alias != null && Src.ident(t.alias).equals(Src.ident(q))) return t;
        for (TableRef t : s.tables) if (t.alias == null && Src.ident(t.table).equals(Src.ident(q))) return t;
        for (TableRef t : s.tables) if (Src.ident(t.table).equals(Src.ident(q))) return t;
        return null;
    }

    private void collectTables(ParserRuleContext root, SqlStatement s, Set<String> cte, Set<Tableview_nameContext> handled) {
        Src.each(root, n -> {
            if (n instanceof Insert_into_clauseContext ic) {
                TableRef t = target(ic.general_table_ref(), "INSERT", s, cte, handled);
                if (t != null) {
                    if (ic.paren_column_list() != null) columns(ic.paren_column_list().column_list(), t.writeColumns);
                    else t.writeColumns.add("*");
                }
            } else if (n instanceof Update_statementContext u) {
                TableRef t = target(u.general_table_ref(), "UPDATE", s, cte, handled);
                if (t != null && u.update_set_clause() != null) {
                    for (Column_based_update_set_clauseContext c : u.update_set_clause().column_based_update_set_clause()) {
                        if (c.column_name() != null) t.writeColumns.add(lastSegment(Src.text(c.column_name())));
                        else if (c.paren_column_list() != null) columns(c.paren_column_list().column_list(), t.writeColumns);
                    }
                }
            } else if (n instanceof Delete_statementContext d) {
                target(d.general_table_ref(), "DELETE", s, cte, handled);
            } else if (n instanceof Merge_statementContext m) {
                List<Selected_tableviewContext> tv = m.selected_tableview();
                TableRef t = null;
                if (!tv.isEmpty() && tv.get(0).tableview_name() != null)
                    t = add(tv.get(0).tableview_name(), "MERGE", tv.get(0).table_alias(), s, cte, handled);
                if (tv.size() > 1 && tv.get(1).tableview_name() != null)
                    add(tv.get(1).tableview_name(), "READ", tv.get(1).table_alias(), s, cte, handled);
                if (t != null) {
                    if (m.merge_update_clause() != null)
                        for (Merge_elementContext e : m.merge_update_clause().merge_element()) t.writeColumns.add(lastSegment(Src.text(e.column_name())));
                    if (m.merge_insert_clause() != null) {
                        if (m.merge_insert_clause().paren_column_list() != null) columns(m.merge_insert_clause().paren_column_list().column_list(), t.writeColumns);
                        else t.writeColumns.add("*");
                    }
                }
            } else if (n instanceof Lock_table_elementContext l) {
                add(l.tableview_name(), "LOCK", null, s, cte, handled);
            } else if (n instanceof Tableview_nameContext tv && !handled.contains(tv)
                    && !(tv.getParent() instanceof Select_list_elementsContext)) {
                add(tv, "READ", aliasOf(tv), s, cte, handled);
            }
        });
    }

    private TableRef target(General_table_refContext g, String access, SqlStatement s, Set<String> cte, Set<Tableview_nameContext> handled) {
        if (g == null || g.dml_table_expression_clause() == null || g.dml_table_expression_clause().tableview_name() == null) return null;
        return add(g.dml_table_expression_clause().tableview_name(), access, g.table_alias(), s, cte, handled);
    }

    private TableRef add(Tableview_nameContext tv, String access, Table_aliasContext alias, SqlStatement s, Set<String> cte,
                         Set<Tableview_nameContext> handled) {
        if (tv == null || tv.identifier() == null) return null; // xmltable and friends
        handled.add(tv);
        TableRef t = new TableRef();
        String id1 = Src.text(tv.identifier());
        if (tv.id_expression() != null) {
            t.schema = id1;
            t.table = Src.text(tv.id_expression());
        } else t.table = id1;
        if (tv.link_name() != null) t.dbLink = Src.text(tv.link_name());
        if (alias != null) t.alias = Src.text(alias);
        String name = Src.ident(t.table);
        if (t.schema == null && (cte.contains(name) || name.equals("dual"))) return null;
        t.access.add(access);
        s.tables.add(t);
        return t;
    }

    private static Table_aliasContext aliasOf(ParseTree tv) {
        for (ParseTree p = tv.getParent(); p != null; p = p.getParent()) {
            if (p instanceof General_table_refContext g) return g.table_alias();
            if (p instanceof Table_ref_auxContext a) return a.table_alias();
            if (p instanceof Selected_tableviewContext sv) return sv.table_alias();
        }
        return null;
    }

    private static void columns(Column_listContext cl, Set<String> into) {
        if (cl == null) return;
        for (Column_nameContext c : cl.column_name()) into.add(lastSegment(Src.text(c)));
    }

    private static String lastSegment(String qualified) {
        String t = Src.norm(qualified);
        return t.substring(t.lastIndexOf('.') + 1);
    }

    /** Column reads and bind variables from expressions; unqualified names only when attribution is unambiguous. */
    private void attributeColumns(ParserRuleContext root, SqlStatement s, Set<String> aliases) {
        Src.each(root, n -> {
            if (!(n instanceof General_elementContext g) || g.getParent() instanceof General_elementContext) return;
            if (Src.hasAncestor(g, Into_clauseContext.class) || Src.hasAncestor(g, Values_clauseContext.class)) {
                if (Src.hasAncestor(g, Values_clauseContext.class)) bindIfVar(g, s);
                return;
            }
            List<General_element_partContext> parts = Src.parts(g);
            for (General_element_partContext p : parts) if (!p.function_argument().isEmpty()) return; // a call, not a column
            List<String> names = new ArrayList<>();
            for (General_element_partContext p : parts) names.add(Src.ident(Src.text(p.id_expression())));
            if (names.isEmpty()) return;
            String first = names.get(0);
            if (names.size() == 1) {
                if (vars.contains(first)) { s.binds.add(Src.norm(Src.text(g))); return; }
                if (aliases.contains(first) || NOT_COLUMNS.contains(first)) return;
                if (s.tables.size() == 1) s.tables.get(0).readColumns.add(Src.norm(Src.text(g)));
                else if (s.tables.size() > 1) s.unresolvedColumns.add(Src.norm(Src.text(g)));
                return;
            }
            String qual = names.get(names.size() - 2), col = Src.norm(Src.text(parts.get(parts.size() - 1).id_expression()));
            for (TableRef t : s.tables) {
                boolean match = qual.equals(Src.ident(t.alias)) && t.alias != null || t.alias == null && qual.equals(Src.ident(t.table));
                if (!match && t.alias != null && qual.equals(Src.ident(t.table))) match = true;
                if (match) { t.readColumns.add(col); return; }
            }
            if (vars.contains(first)) s.binds.add(Src.norm(Src.text(parts.get(0).id_expression())));
        });
    }

    private void bindIfVar(General_elementContext g, SqlStatement s) {
        List<General_element_partContext> parts = Src.parts(g);
        if (parts.isEmpty()) return;
        String first = Src.ident(Src.text(parts.get(0).id_expression()));
        if (vars.contains(first)) s.binds.add(Src.norm(Src.text(parts.get(0).id_expression())));
    }

    // ------------------------------------------------------------------ dynamic SQL

    private void dynamic(ParserRuleContext stmt, ExpressionContext expr, String kind, boolean inLoop, boolean bulk) {
        SqlStatement s = new SqlStatement();
        s.kind = kind;
        s.context = context;
        s.line = stmt.getStart().getLine();
        s.offset = stmt.getStart().getStartIndex();
        s.endLine = Src.endLine(stmt);
        s.text = Src.norm(Src.text(stmt));
        s.inLoop = inLoop;
        s.bulk = bulk;
        s.dynamic = true;
        s.dynamicExpression = Src.norm(Src.text(expr));
        Src.each(stmt, n -> {
            if (n instanceof Into_clauseContext ic) {
                for (ParseTree ch : ic.children)
                    if (ch instanceof General_elementContext || ch instanceof Bind_variableContext) s.intoTargets.add(Src.norm(Src.text((ParserRuleContext) ch)));
            } else if (n instanceof Using_clauseContext u) s.binds.add(Src.norm(Src.text(u)).replaceFirst("(?i)^using\\s+", ""));
        });
        // string literal fragments and whether the expression is *only* literals joined by ||
        StringBuilder joined = new StringBuilder();
        boolean[] onlyLiterals = {true};
        int[] literals = {0};
        Src.each(expr, n -> {
            if (!(n instanceof TerminalNode t)) return;
            int ty = t.getSymbol().getType();
            if (ty == PlSqlLexer.CHAR_STRING || ty == PlSqlLexer.NATIONAL_CHAR_STRING_LIT) {
                joined.append(decode(t.getText())).append(' ');
                literals[0]++;
            } else if (!t.getText().equals("|") && !t.getText().equals("(") && !t.getText().equals(")")) onlyLiterals[0] = false;
        });
        String sql = joined.toString().strip();
        if (literals[0] == 0) {
            s.dynamicConfidence = "UNKNOWN";
        } else if (onlyLiterals[0] && parseLiteral(sql, s, inLoop)) {
            s.dynamicConfidence = "LITERAL";
        } else {
            s.dynamicConfidence = "PARTIAL";
            inferTables(sql, s);
        }
        out.add(s);
    }

    /** Re-parse a fully literal dynamic statement and pull its tables; false if it does not parse as SQL/PLSQL. */
    private boolean parseLiteral(String sql, SqlStatement into, boolean inLoop) {
        String text = sql.endsWith(";") || sql.toLowerCase().matches("(?s).*\\bend\\s*;?\\s*$") ? sql : sql + ";";
        var pu = PlSqlParserFacade.parseText(Path.of("dynamic-sql"), "primary", text, List.of());
        if (!pu.ok()) return false;
        SqlExtractor nested = new SqlExtractor(vars, "DYNAMIC");
        nested.scan(pu.tree());
        for (SqlStatement n : nested.out) {
            for (TableRef t : n.tables) {
                t.confidence = "EXACT";
                into.tables.add(t);
            }
            into.binds.addAll(n.binds);
        }
        Matcher m = Pattern.compile("(?i)^\\s*([a-z]+)").matcher(sql);
        if (nested.out.isEmpty() && m.find()) into.dynamicKind = m.group(1).toUpperCase();
        else if (!nested.out.isEmpty()) into.dynamicKind = nested.out.get(0).kind;
        return true;
    }

    private void inferTables(String sql, SqlStatement into) {
        Matcher m = DYN_TABLE.matcher(sql);
        Matcher verb = Pattern.compile("(?i)^\\s*([a-z]+)").matcher(sql);
        if (verb.find()) into.dynamicKind = verb.group(1).toUpperCase();
        while (m.find()) {
            String name = m.group(2);
            String bare = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            if (DYN_STOP.contains(bare)) continue;
            String v = m.group(1).toLowerCase().replaceAll("\\s+", " ");
            String access = v.startsWith("insert") ? "INSERT" : v.startsWith("merge") ? "MERGE" : v.startsWith("delete") ? "DELETE"
                    : v.equals("update") ? "UPDATE" : v.startsWith("lock") ? "LOCK"
                    : (v.startsWith("truncate") || v.startsWith("drop") || v.startsWith("alter") || v.startsWith("create") || v.startsWith("comment")) ? "DDL" : "READ";
            TableRef t = new TableRef();
            int dot = name.indexOf('.');
            if (dot > 0) { t.schema = name.substring(0, dot); t.table = name.substring(dot + 1); } else t.table = name;
            t.access.add(access);
            t.confidence = "INFERRED";
            into.tables.add(t);
        }
    }

    private static String decode(String lit) {
        String t = lit.strip();
        if (t.startsWith("N") || t.startsWith("n")) t = t.substring(1);
        if ((t.startsWith("q'") || t.startsWith("Q'")) && t.length() > 5) return t.substring(3, t.length() - 2);
        if (t.length() >= 2 && t.startsWith("'")) t = t.substring(1, t.length() - 1);
        return t.replace("''", "'");
    }
}
