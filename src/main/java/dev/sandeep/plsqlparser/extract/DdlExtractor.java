package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.TableDef;
import dev.sandeep.plsqlparser.model.TableDef.Column;
import dev.sandeep.plsqlparser.model.TableDef.ForeignKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads table shape out of DDL that happens to be in the scanned folder: {@code CREATE TABLE} (columns, types, nullability,
 * primary / unique / foreign keys, inline or out of line) and constraints added with {@code ALTER TABLE ... ADD}.
 * Anything else about a table (storage, partitioning, indexes) is ignored; the statement is still listed as skipped.
 */
final class DdlExtractor {
    private DdlExtractor() {}

    static TableDef createTable(Create_tableContext c, String file) {
        if (c.table_name() == null) return null;
        TableDef t = new TableDef();
        t.name = unquote(Src.text(c.table_name()));
        if (c.schema_name() != null) t.schema = unquote(Src.text(c.schema_name()));
        t.file = file;
        t.line = c.getStart().getLine();
        t.endLine = Src.endLine(c);
        Relational_tableContext rt = c.relational_table();
        if (rt == null) return t;
        for (Relational_propertyContext p : rt.relational_property()) {
            if (p.column_definition() != null) column(t, p.column_definition());
            else if (p.out_of_line_constraint() != null) outOfLine(t, p.out_of_line_constraint());
        }
        return t;
    }

    /** {@code ALTER TABLE x ADD CONSTRAINT ...}: returns a constraint-only definition to be merged with the CREATE (which may be elsewhere). */
    static TableDef alterTable(Alter_tableContext a, String file) {
        if (a.tableview_name() == null || a.constraint_clauses() == null) return null;
        List<Out_of_line_constraintContext> cs = a.constraint_clauses().out_of_line_constraint();
        if (cs.isEmpty()) return null;
        TableDef t = new TableDef();
        Tableview_nameContext tv = a.tableview_name();
        if (tv.identifier() == null) return null;
        if (tv.id_expression() != null) {
            t.schema = unquote(Src.text(tv.identifier()));
            t.name = unquote(Src.text(tv.id_expression()));
        } else t.name = unquote(Src.text(tv.identifier()));
        t.file = file;
        t.line = a.getStart().getLine();
        t.endLine = Src.endLine(a);
        t.alterOnly = true;
        for (Out_of_line_constraintContext c : cs) outOfLine(t, c);
        return t.primaryKey.isEmpty() && t.uniqueKeys.isEmpty() && t.foreignKeys.isEmpty() ? null : t;
    }

    private static void column(TableDef t, Column_definitionContext cd) {
        String name = unquote(Src.text(cd.column_name()));
        String type = cd.datatype() != null ? Src.norm(Src.text(cd.datatype())) : cd.type_name() != null ? Src.norm(Src.text(cd.type_name())) : "";
        boolean notNull = false, pk = false, unique = false;
        for (Inline_constraintContext ic : cd.inline_constraint()) {
            if (ic.NOT() != null && ic.NULL_() != null) notNull = true;
            if (ic.PRIMARY() != null) { pk = true; notNull = true; }
            if (ic.UNIQUE() != null) unique = true;
            if (ic.references_clause() != null)
                t.foreignKeys.add(reference(ic.constraint_name() == null ? null : Src.text(ic.constraint_name()), List.of(name), ic.references_clause(), ""));
        }
        if (pk) t.primaryKey.add(name);
        if (unique) t.uniqueKeys.add(List.of(name));
        t.columns.add(new Column(name, type, notNull, pk, unique, cd.expression() == null ? null : Src.norm(Src.text(cd.expression()))));
    }

    private static void outOfLine(TableDef t, Out_of_line_constraintContext c) {
        String cname = c.constraint_name() == null ? null : Src.text(c.constraint_name());
        if (c.foreign_key_clause() != null) {
            Foreign_key_clauseContext f = c.foreign_key_clause();
            t.foreignKeys.add(reference(cname, columns(f.paren_column_list()), f.references_clause(), f.on_delete_clause() == null ? "" : Src.norm(Src.text(f.on_delete_clause()))));
        } else if (c.PRIMARY() != null) {
            List<String> cols = new ArrayList<>();
            for (Column_nameContext cn : c.column_name()) cols.add(unquote(Src.text(cn)));
            t.primaryKey.clear();
            t.primaryKey.addAll(cols);
        } else if (c.UNIQUE() != null) {
            List<String> cols = new ArrayList<>();
            for (Column_nameContext cn : c.column_name()) cols.add(unquote(Src.text(cn)));
            t.uniqueKeys.add(cols);
        }
    }

    private static ForeignKey reference(String name, List<String> cols, References_clauseContext r, String onDelete) {
        String od = !onDelete.isEmpty() ? onDelete : r.CASCADE() != null ? "ON DELETE CASCADE" : r.NULL_() != null ? "ON DELETE SET NULL" : "";
        return new ForeignKey(name == null ? null : unquote(name), cols, unquote(Src.text(r.tableview_name())), columns(r.paren_column_list()), od);
    }

    private static List<String> columns(Paren_column_listContext p) {
        List<String> out = new ArrayList<>();
        if (p == null || p.column_list() == null) return out;
        for (Column_nameContext c : p.column_list().column_name()) out.add(unquote(Src.text(c)));
        return out;
    }

    /** Upper-case, quotes removed ({@code "Orders"} -> ORDERS). */
    private static String unquote(String s) {
        return Src.norm(s).replace("\"", "").toUpperCase();
    }
}
