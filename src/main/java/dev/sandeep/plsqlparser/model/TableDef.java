package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A table as declared by DDL found in the scanned files ({@code CREATE TABLE}, plus constraints added by {@code ALTER TABLE ... ADD}).
 * {@code alterOnly} is true when only an ALTER was seen (the CREATE is in a file we do not have).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class TableDef {
    public record Column(String name, String type, boolean notNull, boolean primaryKey, boolean unique, String defaultValue) {}

    public record ForeignKey(String name, List<String> columns, String refTable, List<String> refColumns, String onDelete) {}

    public String schema;
    public String name;
    public String file;
    public int line, endLine;
    public boolean alterOnly;
    public final List<Column> columns = new ArrayList<>();
    public final List<String> primaryKey = new ArrayList<>();
    public final List<List<String>> uniqueKeys = new ArrayList<>();
    public final List<ForeignKey> foreignKeys = new ArrayList<>();

    /** Upper-case key matching {@link SqlStatement.TableRef#key()}. */
    public String key() {
        String n = name.toUpperCase().replace("\"", "");
        return schema == null ? n : schema.toUpperCase().replace("\"", "") + "." + n;
    }
}
