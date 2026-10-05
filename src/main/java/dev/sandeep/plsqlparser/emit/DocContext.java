package dev.sandeep.plsqlparser.emit;

import dev.sandeep.plsqlparser.analyze.DependencyGraph;
import dev.sandeep.plsqlparser.analyze.MigrationOrder.Order;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;

import java.util.*;
import java.util.function.Function;

/** Everything the emitters share: the model, graph, ordering, output paths and cross-reference lookups. */
final class DocContext {
    /** One use of a table by a routine/unit block. */
    record TableUse(String routineId, String file, int line, String kind, Set<String> access, Set<String> readCols,
                    Set<String> writeCols, boolean inLoop, boolean dynamic, String confidence) {}

    final StructureModel model;
    final DependencyGraph graph;
    final Order order;
    final Function<String, String> fileText;
    final String toolVersion;

    /** routine id -> routine (the implementation if there is one). */
    final Map<String, Routine> routineById = new TreeMap<>();
    final Map<Routine, PlsqlUnit> unitOf = new IdentityHashMap<>();
    final Map<Routine, Routine> specOf = new IdentityHashMap<>();
    final Map<String, String> cardPath = new TreeMap<>();
    final Map<PlsqlUnit, String> unitCardPath = new IdentityHashMap<>();
    final Map<String, String> pkgPath = new TreeMap<>();
    final Map<String, String> tablePath = new TreeMap<>();
    final Map<String, Set<String>> calledBy = new TreeMap<>();
    final Map<String, List<TableUse>> tableUses = new TreeMap<>();
    final Set<String> alternateOnlyIds = new TreeSet<>();
    private final Map<String, Set<String>> usedNames = new HashMap<>();
    private final Map<String, List<String>> lineCache = new HashMap<>();

    DocContext(StructureModel model, DependencyGraph graph, Order order, Function<String, String> fileText, String toolVersion) {
        this.model = model;
        this.graph = graph;
        this.order = order;
        this.fileText = fileText;
        this.toolVersion = toolVersion;
        for (PlsqlUnit u : model.units) registerUnit(u);
        for (Routine r : model.alternateOnlyRoutines) {
            alternateOnlyIds.add(r.id);
            registerRoutine(r, null, "objects/" + Md.slug(r.owner == null ? "_standalone" : r.owner));
        }
        for (PlsqlUnit u : model.units) linkSpecs(u);
        for (DependencyGraph.Edge e : graph.edges()) if (e.type.equals("CALLS")) calledBy.computeIfAbsent(e.to, k -> new TreeSet<>()).add(e.from);
        for (String t : graph.tables()) tablePath.put(t, "tables/" + t.replaceAll("[^A-Za-z0-9_.$#-]", "_") + ".md");
        collectTableUses();
    }

    // ------------------------------------------------------------------ registration

    private void registerUnit(PlsqlUnit u) {
        String dir;
        switch (u.kind) {
            case "PACKAGE_SPEC", "PACKAGE_BODY" -> {
                dir = "objects/" + Md.slug(u.name);
                pkgPath.putIfAbsent(u.key(), dir + "/_package.md");
            }
            case "PROCEDURE", "FUNCTION" -> dir = "objects/_standalone";
            case "TRIGGER" -> {
                dir = "objects/_triggers";
                unitCardPath.put(u, dir + "/" + unique(dir, Md.slug(u.name)) + ".md");
            }
            default -> {
                dir = "objects/_anonymous";
                unitCardPath.put(u, dir + "/" + unique(dir, Md.slug(u.name)) + ".md");
            }
        }
        if (u.kind.equals("PACKAGE_BODY") && !u.outline.isEmpty()) unitCardPath.put(u, dir + "/_init.md");
        for (Routine r : u.decls.routines) registerRoutine(r, u, dir);
    }

    private void registerRoutine(Routine r, PlsqlUnit u, String dir) {
        if (r.forwardDeclaration || r.id == null) return;
        boolean mergedIntoBody = !r.hasBody && r.bodyDefLine != null;
        if (u != null) unitOf.put(r, u);
        if (!mergedIntoBody) {
            String name = r.id.contains(".") ? r.id.substring(r.id.indexOf('.') + 1) : r.id;
            cardPath.put(r.id, dir + "/" + unique(dir, Md.slug(name)) + ".md");
            Routine prev = routineById.get(r.id);
            if (prev == null || r.hasBody) routineById.put(r.id, r);
        }
        for (Routine n : r.locals.routines) registerRoutine(n, u, dir);
    }

    private String unique(String dir, String base) {
        Set<String> used = usedNames.computeIfAbsent(dir, k -> new HashSet<>());
        String name = base;
        for (int i = 2; !used.add(name); i++) name = base + "_" + i;
        return name;
    }

    private void linkSpecs(PlsqlUnit u) {
        if (!u.kind.equals("PACKAGE_SPEC")) return;
        for (Routine sr : u.decls.routines) {
            if (sr.bodyDefLine == null) continue;
            Routine body = routineById.get(sr.id);
            if (body != null && body != sr) specOf.put(body, sr);
        }
    }

    private void collectTableUses() {
        for (PlsqlUnit u : model.units) {
            addUses(u.name + ".<" + (u.kind.equals("TRIGGER") ? "trigger" : "init") + ">", u.file, u.sql);
            for (Routine r : u.decls.routines) walkRoutine(r);
        }
        for (Routine r : model.alternateOnlyRoutines) walkRoutine(r);
        tableUses.values().forEach(l -> l.sort(Comparator.comparing(TableUse::routineId).thenComparingInt(TableUse::line)));
    }

    private void walkRoutine(Routine r) {
        if (r.id != null) addUses(r.id, r.file, r.sql);
        for (Routine n : r.locals.routines) walkRoutine(n);
    }

    private void addUses(String id, String file, List<SqlStatement> sql) {
        for (SqlStatement s : sql)
            for (TableRef t : s.tables)
                tableUses.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(new TableUse(id, file, s.line, s.kind,
                        t.access, t.readColumns, t.writeColumns, s.inLoop, s.dynamic, t.confidence));
    }

    // ------------------------------------------------------------------ lookups

    List<String> lines(String file) {
        return lineCache.computeIfAbsent(file, f -> Arrays.asList(fileText.apply(f).split("\r?\n", -1)));
    }

    /** Original source lines [from, to] with line numbers, exactly as in the file. */
    String numberedSource(String file, int from, int to) {
        List<String> l = lines(file);
        StringBuilder b = new StringBuilder();
        int w = String.valueOf(Math.min(to, l.size())).length();
        for (int i = from; i <= to && i <= l.size(); i++) b.append(String.format("%" + w + "d| ", i)).append(l.get(i - 1)).append('\n');
        return b.toString();
    }

    String rawSource(String file, int from, int to) {
        List<String> l = lines(file);
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to && i <= l.size(); i++) b.append(l.get(i - 1)).append('\n');
        return b.toString();
    }

    /** Link to a routine's card (or plain code if it has none). */
    String routineLink(String fromFile, String id) {
        String p = cardPath.get(id);
        return p == null ? Md.code(id) : Md.mdLink(id, fromFile, p);
    }

    String tableLink(String fromFile, String key) {
        String p = tablePath.get(key);
        return p == null ? Md.code(key) : Md.mdLink(key, fromFile, p);
    }

    String rel(String fromFile, String toFile) {
        return Md.link(fromFile, toFile);
    }

    boolean fileHasVariants(String file) {
        return model.files.stream().anyMatch(f -> f.file().equals(file) && f.variant().equals("alternate"));
    }

    Routine specRoutine(Routine r) {
        return specOf.get(r);
    }
}
