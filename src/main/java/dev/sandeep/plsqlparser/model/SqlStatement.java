package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One SQL statement embedded in PL/SQL (static, or dynamic with best-effort inference). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SqlStatement {
    /** A table/view/synonym reference inside a statement, with how it is accessed. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class TableRef {
        public String schema;
        public String table;
        public String dbLink;
        public String alias;
        /** READ, INSERT, UPDATE, DELETE, MERGE, LOCK */
        public final Set<String> access = new LinkedHashSet<>();
        public final Set<String> readColumns = new LinkedHashSet<>();
        public final Set<String> writeColumns = new LinkedHashSet<>();
        /** EXACT (static SQL) or INFERRED (extracted from a dynamic SQL string). */
        public String confidence = "EXACT";

        /** Upper-case key used for cross-references, e.g. SCOTT.EMP or EMP. */
        public String key() {
            String t = table == null ? "?" : table.toUpperCase().replace("\"", "");
            return schema == null ? t : schema.toUpperCase().replace("\"", "") + "." + t;
        }
    }

    /** An equality between columns of two different tables in the same statement (join condition): evidence of a relationship. */
    public record JoinRef(String leftTable, String leftColumn, String rightTable, String rightColumn) {}

    public final List<JoinRef> joins = new ArrayList<>();
    public String kind;          // SELECT, INSERT, UPDATE, DELETE, MERGE, LOCK, EXECUTE_IMMEDIATE, OPEN_FOR, CURSOR_LOOP, CURSOR_DECL
    public String context;       // BODY, CURSOR_DECL, INIT_BLOCK, TRIGGER, ...
    public int line, endLine;
    /** Character offset of the statement start in its source (internal: links outline steps to statements). */
    @com.fasterxml.jackson.annotation.JsonIgnore public int offset;
    public String text;          // verbatim (whitespace-normalised) statement text
    public final List<TableRef> tables = new ArrayList<>();
    /** PL/SQL variables / parameters / :binds feeding or receiving values in this statement. */
    public final Set<String> binds = new LinkedHashSet<>();
    public final Set<String> intoTargets = new LinkedHashSet<>();
    /** Unqualified columns that could not be attributed to one table (multi-table statements). */
    public final Set<String> unresolvedColumns = new LinkedHashSet<>();
    public boolean inLoop;       // executed once per loop iteration (row-by-row risk)
    public boolean bulk;         // FORALL / BULK COLLECT
    public boolean forUpdate;
    public boolean dynamic;
    public String dynamicConfidence; // LITERAL | PARTIAL | UNKNOWN
    public String dynamicExpression; // original expression for dynamic SQL
    public String dynamicKind;       // leading verb / inner statement kind of dynamic SQL, when known
}
