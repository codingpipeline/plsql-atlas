package dev.sandeep.plsqlparser.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;

import java.util.*;

/**
 * M3: routine/table dependency graph built from the structure model.
 * Nodes: routines, unit-level blocks (init block / trigger), tables, Oracle built-in packages, external / unresolved targets.
 * Edges: CALLS, READS, WRITES.
 */
public final class DependencyGraph {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Node(String id, String type, String label, String owner, String file, int line, String visibility) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class Edge {
        public final String from, to, type;
        public int count;
        public boolean ambiguous, inLoop;
        public final Set<String> detail = new TreeSet<>(); // DML kinds for table edges, resolution for call edges

        Edge(String from, String to, String type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
    }

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edgeMap = new LinkedHashMap<>();

    public Collection<Edge> edges() {
        return edgeMap.values();
    }

    // ------------------------------------------------------------------ build

    public static DependencyGraph build(StructureModel m) {
        DependencyGraph g = new DependencyGraph();
        for (PlsqlUnit u : m.units) g.addUnit(u);
        for (Routine r : m.alternateOnlyRoutines) g.addRoutine(r, null);
        return g;
    }

    private void addUnit(PlsqlUnit u) {
        for (Routine r : u.decls.routines) addRoutine(r, u);
        if (!u.sql.isEmpty() || !u.calls.isEmpty()) {
            String id = unitBlockId(u);
            nodes.putIfAbsent(id, new Node(id, "UNIT_BLOCK", id, u.name, u.file, u.line, null));
            addEdges(id, u.calls, u.sql);
        }
    }

    private static String unitBlockId(PlsqlUnit u) {
        return u.kind.equals("TRIGGER") ? "TRIGGER:" + u.name.toUpperCase()
                : u.name.toUpperCase() + ".<" + (u.kind.equals("ANONYMOUS_BLOCK") ? "anonymous" : "init") + ">";
    }

    private void addRoutine(Routine r, PlsqlUnit u) {
        if (r.id != null && !r.forwardDeclaration)
            nodes.putIfAbsent(r.id, new Node(r.id, "ROUTINE", r.signature, r.owner, r.file, r.line, r.visibility));
        if (r.hasBody || !r.calls.isEmpty() || !r.sql.isEmpty()) addEdges(r.id, r.calls, r.sql);
        for (Routine n : r.locals.routines) addRoutine(n, u);
    }

    private void addEdges(String from, List<CallSite> calls, List<SqlStatement> sql) {
        for (CallSite c : calls) {
            List<String> targets = new ArrayList<>();
            boolean amb = false;
            if (c.targetId != null) targets.add(c.targetId);
            else if (!c.candidates.isEmpty()) { targets.addAll(c.candidates); amb = true; }
            else if (CallSite.BUILTIN.equals(c.resolution)) targets.add(external("BUILTIN", c.builtinPackage, "BUILTIN_PACKAGE"));
            else if (CallSite.EXTERNAL.equals(c.resolution) || CallSite.UNRESOLVED_IN_PACKAGE.equals(c.resolution))
                targets.add(external("EXTERNAL", c.callee.toUpperCase(), "EXTERNAL"));
            else targets.add(external("UNRESOLVED", c.callee.toUpperCase(), "UNRESOLVED"));
            for (String t : targets) {
                Edge e = edge(from, t, "CALLS");
                e.count++;
                e.ambiguous |= amb;
                e.inLoop |= c.inLoop;
                e.detail.add(c.resolution);
            }
        }
        for (SqlStatement s : sql) {
            for (TableRef t : s.tables) {
                String tid = "TABLE:" + t.key();
                nodes.putIfAbsent(tid, new Node(tid, "TABLE", t.key(), null, null, 0, null));
                for (String a : t.access) {
                    Edge e = edge(from, tid, a.equals("READ") || a.equals("LOCK") ? "READS" : "WRITES");
                    e.count++;
                    e.inLoop |= s.inLoop;
                    e.detail.add(a + (t.confidence.equals("INFERRED") ? "(inferred)" : ""));
                }
            }
        }
    }

    private String external(String prefix, String name, String type) {
        String id = prefix + ":" + name;
        nodes.putIfAbsent(id, new Node(id, type, name, null, null, 0, null));
        return id;
    }

    private Edge edge(String from, String to, String type) {
        return edgeMap.computeIfAbsent(from + "|" + to + "|" + type, k -> new Edge(from, to, type));
    }

    // ------------------------------------------------------------------ queries

    public Map<String, Set<String>> callees() {
        return adjacency(true);
    }

    public Map<String, Set<String>> callers() {
        return adjacency(false);
    }

    private Map<String, Set<String>> adjacency(boolean forward) {
        Map<String, Set<String>> m = new TreeMap<>();
        for (Edge e : edgeMap.values()) {
            if (!e.type.equals("CALLS")) continue;
            m.computeIfAbsent(forward ? e.from : e.to, k -> new TreeSet<>()).add(forward ? e.to : e.from);
        }
        return m;
    }

    /** Routines (transitively) reachable from {@code id} through CALLS edges, excluding itself. */
    public Set<String> reachableFrom(String id) {
        Map<String, Set<String>> callees = callees();
        Set<String> seen = new TreeSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(id));
        while (!work.isEmpty()) {
            for (String n : callees.getOrDefault(work.pop(), Set.of())) if (seen.add(n)) work.push(n);
        }
        seen.remove(id);
        return seen;
    }

    /** Strongly connected call groups with more than one member, or a routine that calls itself (recursion). */
    public List<List<String>> recursionGroups() {
        Map<String, Set<String>> adj = new TreeMap<>(); // unambiguous edges only: overload guesses would invent recursion
        for (Edge e : edgeMap.values())
            if (e.type.equals("CALLS") && !e.ambiguous) adj.computeIfAbsent(e.from, k -> new TreeSet<>()).add(e.to);
        List<List<String>> out = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> on = new HashSet<>();
        int[] counter = {0};
        for (String v : new TreeSet<>(adj.keySet())) if (!index.containsKey(v)) strong(v, adj, index, low, stack, on, counter, out);
        return out;
    }

    private void strong(String v, Map<String, Set<String>> adj, Map<String, Integer> index, Map<String, Integer> low,
                        Deque<String> stack, Set<String> on, int[] counter, List<List<String>> out) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        on.add(v);
        for (String w : adj.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strong(w, adj, index, low, stack, on, counter, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (on.contains(w)) low.put(v, Math.min(low.get(v), index.get(w)));
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do { w = stack.pop(); on.remove(w); comp.add(w); } while (!w.equals(v));
            if (comp.size() > 1 || adj.getOrDefault(v, Set.of()).contains(v)) {
                Collections.sort(comp);
                out.add(comp);
            }
        }
    }

    /** Public routines nobody in the scanned code calls (may still be API entry points). */
    public List<String> uncalledPublic() {
        Set<String> called = callers().keySet();
        List<String> out = new ArrayList<>();
        for (Node n : nodes.values())
            if (n.type().equals("ROUTINE") && n.visibility() != null && !called.contains(n.id())
                    && (n.visibility().equals("PUBLIC") || n.visibility().equals("STANDALONE"))) out.add(n.id());
        Collections.sort(out);
        return out;
    }

    public List<String> uncalledPublicRoutines() {
        return uncalledPublic();
    }

    /** Private routines nobody calls: dead code candidates. */
    public List<String> uncalledPrivateRoutines() {
        Set<String> called = callers().keySet();
        List<String> out = new ArrayList<>();
        for (Node n : nodes.values())
            if (n.type().equals("ROUTINE") && "PRIVATE".equals(n.visibility()) && !called.contains(n.id())) out.add(n.id());
        Collections.sort(out);
        return out;
    }

    public record TableImpact(String table, List<String> readers, List<String> writers, List<String> transitiveWriters) {}

    /** Who reads/writes a table directly, and who can reach a writer through calls. */
    public TableImpact impactOf(String tableKey) {
        String tid = "TABLE:" + tableKey.toUpperCase();
        Set<String> readers = new TreeSet<>(), writers = new TreeSet<>();
        for (Edge e : edgeMap.values()) {
            if (!e.to.equals(tid)) continue;
            (e.type.equals("READS") ? readers : writers).add(e.from);
        }
        Map<String, Set<String>> callers = callers();
        Set<String> transitive = new TreeSet<>();
        Deque<String> work = new ArrayDeque<>(writers);
        while (!work.isEmpty()) for (String c : callers.getOrDefault(work.pop(), Set.of())) if (transitive.add(c)) work.push(c);
        transitive.removeAll(writers);
        return new TableImpact(tableKey.toUpperCase(), new ArrayList<>(readers), new ArrayList<>(writers), new ArrayList<>(transitive));
    }

    public List<String> tables() {
        List<String> out = new ArrayList<>();
        for (Node n : nodes.values()) if (n.type().equals("TABLE")) out.add(n.label());
        Collections.sort(out);
        return out;
    }
}
