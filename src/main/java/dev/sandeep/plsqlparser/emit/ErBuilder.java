package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.TableDef.ForeignKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity-relationship view of the tables the code touches. Facts come from three places and are labelled by origin:
 * DDL in the scanned files (columns, types, keys, declared foreign keys), the SQL in PL/SQL (columns actually read or written) and
 * join conditions ({@code a.x = b.y}) which give <em>inferred</em> relationships when no foreign key is declared.
 */
final class ErBuilder {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Col(String name, String type, boolean pk, boolean fk, boolean unique, boolean notNull, boolean inDdl, boolean read, boolean written) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Entity(String key, String mermaid, String file, int line, boolean ddl, List<Col> columns, List<String> readBy, List<String> writtenBy) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Relation(String child, List<String> childColumns, String parent, List<String> parentColumns, String origin, String cardinality,
                    boolean required, String name, int weight, List<String> evidence) {}

    record Er(List<Entity> entities, List<Relation> relations, List<List<String>> clusters, List<String> notes) {}

    private ErBuilder() {}

    static Er build(DocContext ctx) {
        StructureModel m = ctx.model;
        // ---- merge DDL (CREATE plus constraints from ALTER, which may live in another file)
        Map<String, TableDef> defs = new TreeMap<>();
        for (TableDef d : m.tableDefs) if (!d.alterOnly) defs.merge(d.key(), d, ErBuilder::mergeInto);
        for (TableDef d : m.tableDefs) if (d.alterOnly) defs.merge(d.key(), d, ErBuilder::mergeInto);

        Set<String> keys = new TreeSet<>(ctx.graph.tables());
        keys.addAll(defs.keySet());
        Resolver resolve = new Resolver(keys);
        for (TableDef d : defs.values()) for (ForeignKey f : d.foreignKeys) resolve.entity(f.refTable());

        // ---- usage of each table in SQL
        Map<String, Set<String>> readBy = new TreeMap<>(), writtenBy = new TreeMap<>();
        Map<String, Set<String>> readCols = new TreeMap<>(), writeCols = new TreeMap<>();
        for (var e : ctx.tableUses.entrySet()) {
            String k = resolve.entity(e.getKey());
            for (DocContext.TableUse u : e.getValue()) {
                boolean w = !u.access().stream().allMatch(a -> a.equals("READ") || a.equals("LOCK"));
                (w ? writtenBy : readBy).computeIfAbsent(k, x -> new TreeSet<>()).add(u.routineId());
                u.readCols().forEach(c -> readCols.computeIfAbsent(k, x -> new TreeSet<>()).add(c.toUpperCase()));
                u.writeCols().forEach(c -> writeCols.computeIfAbsent(k, x -> new TreeSet<>()).add(c.toUpperCase()));
            }
        }

        // ---- entities
        List<Entity> entities = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String k : resolve.all()) {
            TableDef d = defs.get(k);
            boolean ddl = d != null && !d.alterOnly && !d.columns.isEmpty();
            Set<String> fkCols = new HashSet<>();
            if (d != null) for (ForeignKey f : d.foreignKeys) fkCols.addAll(f.columns());
            List<Col> cols = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (ddl) for (var c : d.columns) {
                boolean pk = d.primaryKey.contains(c.name());
                cols.add(new Col(c.name(), c.type(), pk, fkCols.contains(c.name()), c.unique() || d.uniqueKeys.stream().anyMatch(u -> u.size() == 1 && u.get(0).equals(c.name())), c.notNull() || pk,
                        true, readCols.getOrDefault(k, Set.of()).contains(c.name()), writeCols.getOrDefault(k, Set.of()).contains(c.name())));
                seen.add(c.name());
            }
            Set<String> extra = new TreeSet<>(readCols.getOrDefault(k, Set.of()));
            extra.addAll(writeCols.getOrDefault(k, Set.of()));
            for (String c : extra) {
                if (c.equals("*") || seen.contains(c)) continue;
                cols.add(new Col(c, "", d != null && d.primaryKey.contains(c), fkCols.contains(c), false, false, false, readCols.getOrDefault(k, Set.of()).contains(c), writeCols.getOrDefault(k, Set.of()).contains(c)));
            }
            entities.add(new Entity(k, mermaidName(k, used), d == null ? null : d.file, d == null ? 0 : d.line, ddl, cols,
                    new ArrayList<>(readBy.getOrDefault(k, Set.of())), new ArrayList<>(writtenBy.getOrDefault(k, Set.of()))));
        }
        Map<String, Entity> byKey = entities.stream().collect(Collectors.toMap(Entity::key, e -> e));

        // ---- declared relationships (foreign keys in DDL)
        List<Relation> rels = new ArrayList<>();
        Set<String> declaredPairs = new HashSet<>();
        for (TableDef d : defs.values()) {
            for (ForeignKey f : d.foreignKeys) {
                String child = d.key(), parent = resolve.entity(f.refTable());
                boolean childUnique = !f.columns().isEmpty() && (new HashSet<>(f.columns()).equals(new HashSet<>(d.primaryKey)) || d.uniqueKeys.stream().anyMatch(u -> new HashSet<>(u).equals(new HashSet<>(f.columns()))));
                boolean required = !f.columns().isEmpty() && f.columns().stream().allMatch(c -> d.columns.stream().anyMatch(col -> col.name().equals(c) && (col.notNull() || d.primaryKey.contains(c))));
                List<String> pcols = f.refColumns().isEmpty() && defs.get(parent) != null ? defs.get(parent).primaryKey : f.refColumns();
                rels.add(new Relation(child, f.columns(), parent, pcols, "DECLARED", childUnique ? "ONE_TO_ONE" : "MANY_TO_ONE", required,
                        f.name() == null ? "FK" : f.name(), 1, List.of(d.file + ":" + d.line + (f.onDelete().isEmpty() ? "" : " " + f.onDelete()))));
                declaredPairs.add(child + ">" + parent);
            }
        }

        // ---- inferred relationships (join conditions in SQL)
        Map<String, InferredAcc> inferred = new LinkedHashMap<>();
        forEachSql(ctx, (id, file, s) -> {
            Map<String, List<SqlStatement.JoinRef>> perPair = new LinkedHashMap<>();
            for (SqlStatement.JoinRef j : s.joins) {
                String a = resolve.entity(j.leftTable()), b = resolve.entity(j.rightTable());
                if (a.equals(b)) continue;
                perPair.computeIfAbsent(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, x -> new ArrayList<>()).add(a.compareTo(b) < 0 ? j
                        : new SqlStatement.JoinRef(j.rightTable(), j.rightColumn(), j.leftTable(), j.leftColumn()));
            }
            for (var pe : perPair.entrySet()) {
                String[] ab = pe.getKey().split("\\|");
                List<String> acols = new ArrayList<>(), bcols = new ArrayList<>();
                for (var j : pe.getValue()) { acols.add(j.leftColumn()); bcols.add(j.rightColumn()); }
                String[] orient = orient(ab[0], acols, ab[1], bcols, defs);   // child, parent, orientation tag
                boolean aIsChild = orient[0].equals(ab[0]);
                List<String> childCols = aIsChild ? acols : bcols, parentCols = aIsChild ? bcols : acols;
                String key = orient[0] + ">" + orient[1] + "|" + childCols + "|" + parentCols;
                inferred.computeIfAbsent(key, x -> new InferredAcc(orient[0], childCols, orient[1], parentCols, orient[2])).add(id + ":" + s.line);
            }
        });
        for (InferredAcc a : inferred.values()) {
            if (declaredPairs.contains(a.child + ">" + a.parent) || declaredPairs.contains(a.parent + ">" + a.child)) continue;
            String name = "join " + a.childCols.stream().collect(Collectors.joining(",")) + " = " + String.join(",", a.parentCols);
            rels.add(new Relation(a.child, a.childCols, a.parent, a.parentCols, "INFERRED", a.cardinality, false, name, a.evidence.size(),
                    a.evidence.size() > 6 ? new ArrayList<>(a.evidence.subList(0, 6)) : a.evidence));
        }
        rels.sort(Comparator.comparing(Relation::origin).thenComparing(Relation::child).thenComparing(Relation::parent));

        // ---- clusters (connected components)
        Map<String, String> uf = new HashMap<>();
        for (Entity e : entities) uf.put(e.key(), e.key());
        for (Relation r : rels) if (uf.containsKey(r.child()) && uf.containsKey(r.parent())) uf.put(find(uf, r.child()), find(uf, r.parent()));
        Map<String, List<String>> comp = new TreeMap<>();
        for (Entity e : entities) comp.computeIfAbsent(find(uf, e.key()), x -> new ArrayList<>()).add(e.key());
        List<List<String>> clusters = new ArrayList<>(comp.values());
        clusters.forEach(Collections::sort);
        clusters.sort(Comparator.comparingInt((List<String> c) -> -c.size()).thenComparing(c -> c.get(0)));

        List<String> notes = new ArrayList<>();
        long noDdl = entities.stream().filter(e -> !e.ddl()).count();
        if (noDdl > 0) notes.add(noDdl + " of " + entities.size() + " table(s) have no CREATE TABLE in the scanned files: their columns are only those the PL/SQL reads or writes, with unknown types and keys.");
        long inf = rels.stream().filter(r -> r.origin().equals("INFERRED")).count();
        if (inf > 0) notes.add(inf + " relationship(s) are INFERRED from join conditions in SQL: they show tables used together, not a guaranteed foreign key. Direction is certain only when a joined column is a known primary key.");
        if (rels.isEmpty()) notes.add("No relationships found: no foreign keys in the scanned DDL and no joins between tables in the SQL.");
        return new Er(entities, rels, clusters, notes);
    }

    // ------------------------------------------------------------------ helpers

    private static TableDef mergeInto(TableDef a, TableDef b) {
        TableDef base = a.alterOnly && !b.alterOnly ? b : a, add = base == a ? b : a;
        if (base.primaryKey.isEmpty()) base.primaryKey.addAll(add.primaryKey);
        for (var u : add.uniqueKeys) if (!base.uniqueKeys.contains(u)) base.uniqueKeys.add(u);
        for (var f : add.foreignKeys) if (!base.foreignKeys.contains(f)) base.foreignKeys.add(f);
        return base;
    }

    private static String[] orient(String a, List<String> acols, String b, List<String> bcols, Map<String, TableDef> defs) {
        boolean bKey = isKey(defs.get(b), bcols), aKey = isKey(defs.get(a), acols);
        if (bKey && !aKey) return new String[]{a, b, "MANY_TO_ONE"};
        if (aKey && !bKey) return new String[]{b, a, "MANY_TO_ONE"};
        if (aKey && bKey) return new String[]{a, b, "ONE_TO_ONE"};
        return new String[]{a, b, "UNKNOWN"};
    }

    private static boolean isKey(TableDef d, List<String> cols) {
        if (d == null) return false;
        Set<String> s = new HashSet<>(cols);
        return (!d.primaryKey.isEmpty() && s.equals(new HashSet<>(d.primaryKey))) || d.uniqueKeys.stream().anyMatch(u -> s.equals(new HashSet<>(u)));
    }

    private static String find(Map<String, String> uf, String x) {
        while (!uf.get(x).equals(x)) { uf.put(x, uf.get(uf.get(x))); x = uf.get(x); }
        return x;
    }

    private static final class InferredAcc {
        final String child, parent, cardinality;
        final List<String> childCols, parentCols;
        final List<String> evidence = new ArrayList<>();

        InferredAcc(String child, List<String> childCols, String parent, List<String> parentCols, String cardinality) {
            this.child = child; this.childCols = childCols; this.parent = parent; this.parentCols = parentCols; this.cardinality = cardinality;
        }

        void add(String e) { if (!evidence.contains(e)) evidence.add(e); }
    }

    /** Maps the many spellings of a table name (SCOTT.EMP, EMP) onto one entity key. */
    private static final class Resolver {
        private final Set<String> keys = new TreeSet<>();

        Resolver(Collection<String> initial) { keys.addAll(initial); }

        String entity(String name) {
            String n = name.toUpperCase().replace("\"", "");
            if (keys.contains(n)) return n;
            String bare = n.substring(n.lastIndexOf('.') + 1);
            List<String> hits = keys.stream().filter(k -> k.substring(k.lastIndexOf('.') + 1).equals(bare)).toList();
            if (hits.size() == 1) return hits.get(0);
            keys.add(n);
            return n;
        }

        Collection<String> all() { return keys; }
    }

    interface SqlVisitor { void visit(String routineId, String file, SqlStatement s); }

    private static void forEachSql(DocContext ctx, SqlVisitor v) {
        for (PlsqlUnit u : ctx.model.units) {
            String id = u.name + ".<" + (u.kind.equals("TRIGGER") ? "trigger" : "init") + ">";
            for (SqlStatement s : u.sql) v.visit(id, u.file, s);
            for (Routine r : u.decls.routines) walk(r, v);
        }
        for (Routine r : ctx.model.alternateOnlyRoutines) walk(r, v);
    }

    private static void walk(Routine r, SqlVisitor v) {
        if (r.id != null) for (SqlStatement s : r.sql) v.visit(r.id, r.file, s);
        for (Routine n : r.locals.routines) walk(n, v);
    }

    // ------------------------------------------------------------------ names for Mermaid

    private static String mermaidName(String key, Set<String> used) {
        String base = key.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "T_" + base;
        String n = base;
        for (int i = 2; !used.add(n); i++) n = base + "_" + i;
        return n;
    }

    private static String mermaidType(String t) {
        if (t == null || t.isBlank()) return "unknown";
        String s = t.replaceAll("\\s+", "").replace(",", "-").replaceAll("[^A-Za-z0-9_()\\[\\]-]", "_");
        return Character.isLetter(s.charAt(0)) ? s : "t_" + s;
    }

    private static String mermaidAttr(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) && s.charAt(0) != '_' ? "c_" + s : s;
    }

    // ------------------------------------------------------------------ output

    static String mermaid(Er er, List<String> cluster) {
        Map<String, Entity> by = er.entities().stream().collect(Collectors.toMap(Entity::key, e -> e));
        Set<String> in = new HashSet<>(cluster);
        StringBuilder b = new StringBuilder("erDiagram\n");
        for (String k : cluster) {
            Entity e = by.get(k);
            b.append("  ").append(e.mermaid()).append(" {\n");
            int shown = 0;
            for (Col c : e.columns()) {
                if (shown++ >= 30) { b.append("    unknown more_columns\n"); break; }
                List<String> kk = new ArrayList<>();
                if (c.pk()) kk.add("PK");
                if (c.fk()) kk.add("FK");
                if (c.unique() && !c.pk()) kk.add("UK");
                b.append("    ").append(mermaidType(c.type())).append(' ').append(mermaidAttr(c.name())).append(kk.isEmpty() ? "" : " " + String.join(", ", kk)).append('\n');
            }
            b.append("  }\n");
        }
        for (Relation r : er.relations()) {
            if (!in.contains(r.child()) || !in.contains(r.parent())) continue;
            String left = by.get(r.child()).mermaid(), right = by.get(r.parent()).mermaid();
            String rel = switch (r.cardinality()) {
                case "ONE_TO_ONE" -> "|o--" + (r.required() ? "||" : "|o");
                case "UNKNOWN" -> "}o--o{";
                default -> "}o--" + (r.required() ? "||" : "|o");
            };
            String label = (r.origin().equals("INFERRED") ? "inferred " : "") + r.name();
            b.append("  ").append(left).append(' ').append(rel).append(' ').append(right).append(" : \"").append(label.replace("\"", "'")).append("\"\n");
        }
        return b.toString();
    }

    static void write(DocContext ctx, Path out, Er er) throws IOException {
        StringBuilder b = new StringBuilder("# Entity-relationship diagram\n\n");
        b.append("The tables this code touches and how they relate. **Declared** relationships are foreign keys found in `CREATE TABLE` / `ALTER TABLE` in the scanned files; ")
                .append("**inferred** ones come from join conditions in the PL/SQL's SQL (`a.x = b.y`), so they show tables used together, not a guaranteed constraint.\n\n");
        for (String n : er.notes()) b.append("- ").append(n).append('\n');
        b.append('\n');
        List<List<String>> multi = er.clusters().stream().filter(c -> c.size() > 1).toList();
        int i = 1;
        for (List<String> c : multi) {
            b.append("## ").append(multi.size() == 1 ? "Diagram" : "Group " + i++ + " (" + c.size() + " tables)").append("\n\n```mermaid\n").append(mermaid(er, c)).append("```\n\n");
        }
        List<String> alone = er.clusters().stream().filter(c -> c.size() == 1).map(c -> c.get(0)).toList();
        if (!alone.isEmpty()) b.append("## Tables with no relationship found\n\n").append(Md.list(alone.stream().map(Md::code).toList())).append('\n');
        b.append("## Tables\n\n").append(Md.table(List.of("Table", "DDL in scanned files", "Columns", "Read by", "Written by"), er.entities().stream().map(e -> List.of(
                ctx.tablePath.containsKey(e.key()) ? Md.mdLink(e.key(), "analysis/er-diagram.md", ctx.tablePath.get(e.key())) : Md.code(e.key()),
                e.ddl() ? Md.code(e.file() + ":" + e.line()) : "no",
                e.columns().isEmpty() ? "—" : e.columns().stream().limit(12).map(c -> c.name() + (c.pk() ? " (PK)" : c.fk() ? " (FK)" : "")).collect(Collectors.joining(", ")) + (e.columns().size() > 12 ? ", …" : ""),
                String.valueOf(e.readBy().size()), String.valueOf(e.writtenBy().size()))).toList())).append('\n');
        b.append("## Relationships\n\n").append(er.relations().isEmpty() ? "_none_\n" : Md.table(List.of("Child", "Columns", "Parent", "Columns", "Origin", "Cardinality", "Evidence"), er.relations().stream().map(r -> List.of(
                Md.code(r.child()), String.join(", ", r.childColumns()), Md.code(r.parent()), String.join(", ", r.parentColumns()), r.origin(),
                r.cardinality().replace('_', ' ').toLowerCase(), String.join("; ", r.evidence().stream().limit(4).map(Md::code).toList()))).toList()));
        Path md = out.resolve("analysis/er-diagram.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, b.toString());
        Files.writeString(out.resolve("analysis/er.json"), new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(er));
    }
}
