package dev.sandeep.plsqlparser.analyze;

import dev.sandeep.plsqlparser.analyze.DependencyGraph.Edge;
import dev.sandeep.plsqlparser.analyze.DependencyGraph.Node;

import java.util.*;

/**
 * Neutral understanding/porting order: callees before callers. Routines are grouped into batches
 * (batch 0 = calls no other routine in the scanned code, batch n = 1 + deepest callee batch); mutually
 * recursive groups are collapsed and reported as cycles. No target technology is implied.
 */
public final class MigrationOrder {
    public record Order(List<List<String>> batches, List<List<String>> cycles, Map<String, Integer> batchOf, Map<String, Integer> rankOf) {}

    private MigrationOrder() {}

    public static Order compute(DependencyGraph g) {
        Set<String> routines = new TreeSet<>();
        for (Node n : g.nodes.values()) if (n.type().equals("ROUTINE")) routines.add(n.id());
        Map<String, Set<String>> deps = new TreeMap<>();
        for (String r : routines) deps.put(r, new TreeSet<>());
        for (Edge e : g.edges())
            if (e.type.equals("CALLS") && routines.contains(e.from) && routines.contains(e.to)) deps.get(e.from).add(e.to);

        // strongly connected components (Tarjan, iterative-safe recursion depth is bounded by routine count)
        Map<String, Integer> idx = new HashMap<>(), low = new HashMap<>(), comp = new HashMap<>();
        Deque<String> st = new ArrayDeque<>();
        Set<String> on = new HashSet<>();
        List<List<String>> comps = new ArrayList<>();
        int[] c = {0};
        for (String r : routines) if (!idx.containsKey(r)) tarjan(r, deps, idx, low, st, on, comps, comp, c);

        List<List<String>> cycles = new ArrayList<>();
        for (List<String> cp : comps) if (cp.size() > 1 || deps.get(cp.get(0)).contains(cp.get(0))) { Collections.sort(cp); cycles.add(cp); }
        cycles.sort(Comparator.comparing(l -> l.get(0)));

        // batch = longest path to a leaf over the condensation
        Map<Integer, Integer> batchOfComp = new HashMap<>();
        Map<String, Integer> batchOf = new TreeMap<>();
        for (String r : routines) batchOf.put(r, batch(comp.get(r), comps, deps, comp, batchOfComp, new HashSet<>()));

        int maxBatch = batchOf.values().stream().max(Integer::compare).orElse(-1);
        List<List<String>> batches = new ArrayList<>();
        for (int b = 0; b <= maxBatch; b++) batches.add(new ArrayList<>());
        batchOf.forEach((r, b) -> batches.get(b).add(r));
        Map<String, Integer> rank = new LinkedHashMap<>();
        int i = 1;
        for (List<String> b : batches) for (String r : b) rank.put(r, i++);
        return new Order(batches, cycles, batchOf, rank);
    }

    private static int batch(int comp, List<List<String>> comps, Map<String, Set<String>> deps, Map<String, Integer> compOf,
                             Map<Integer, Integer> memo, Set<Integer> path) {
        Integer known = memo.get(comp);
        if (known != null) return known;
        int best = -1;
        path.add(comp);
        for (String r : comps.get(comp))
            for (String d : deps.get(r)) {
                int dc = compOf.get(d);
                if (dc != comp && !path.contains(dc)) best = Math.max(best, batch(dc, comps, deps, compOf, memo, path));
            }
        path.remove(comp);
        memo.put(comp, best + 1);
        return best + 1;
    }

    private static void tarjan(String v, Map<String, Set<String>> deps, Map<String, Integer> idx, Map<String, Integer> low, Deque<String> st,
                               Set<String> on, List<List<String>> comps, Map<String, Integer> comp, int[] c) {
        idx.put(v, c[0]);
        low.put(v, c[0]++);
        st.push(v);
        on.add(v);
        for (String w : deps.get(v)) {
            if (!idx.containsKey(w)) {
                tarjan(w, deps, idx, low, st, on, comps, comp, c);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (on.contains(w)) low.put(v, Math.min(low.get(v), idx.get(w)));
        }
        if (low.get(v).equals(idx.get(v))) {
            List<String> cp = new ArrayList<>();
            String w;
            do {
                w = st.pop();
                on.remove(w);
                cp.add(w);
                comp.put(w, comps.size());
            } while (!w.equals(v));
            comps.add(cp);
        }
    }
}
