package dev.sandeep.plsqlparser.analyze;

import dev.sandeep.plsqlparser.analyze.DependencyGraph.Edge;
import dev.sandeep.plsqlparser.analyze.DependencyGraph.Node;

import java.util.*;

/** Package-level Mermaid diagram: packages/standalone routines, tables and Oracle built-ins. */
public final class MermaidWriter {
    private MermaidWriter() {}

    public static String packageDiagram(DependencyGraph g) {
        StringBuilder sb = new StringBuilder("graph LR\n");
        Map<String, Map<String, int[]>> agg = new TreeMap<>(); // "from|type" -> to -> {count, loop}
        Set<String> declared = new TreeSet<>();
        for (Edge e : g.edges()) {
            String from = group(g, e.from), to = group(g, e.to);
            if (from.equals(to)) continue;
            declared.add(from);
            declared.add(to);
            int[] c = agg.computeIfAbsent(from + "|" + e.type, k -> new TreeMap<>()).computeIfAbsent(to, k -> new int[2]);
            c[0] += e.count;
            if (e.inLoop) c[1] = 1;
        }
        for (String d : declared) sb.append("  ").append(id(d)).append(shape(d)).append('\n');
        for (var from : agg.entrySet()) {
            String[] ft = from.getKey().split("\\|");
            for (var to : from.getValue().entrySet()) {
                String arrow = ft[1].equals("CALLS") ? "-->" : ft[1].equals("READS") ? "-.->" : "==>";
                String label = ft[1].equals("CALLS") ? to.getValue()[0] + "x" : ft[1].toLowerCase();
                sb.append("  ").append(id(ft[0])).append(' ').append(arrow).append('|').append(label).append("| ").append(id(to.getKey())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String group(DependencyGraph g, String nodeId) {
        Node n = g.nodes.get(nodeId);
        if (nodeId.startsWith("TABLE:") || nodeId.startsWith("BUILTIN:") || nodeId.startsWith("EXTERNAL:") || nodeId.startsWith("UNRESOLVED:")) return nodeId;
        if (nodeId.startsWith("TRIGGER:")) return nodeId;
        int dot = nodeId.indexOf('.');
        return dot > 0 ? nodeId.substring(0, dot) : (n != null && n.owner() != null ? n.owner().toUpperCase() : nodeId);
    }

    private static String id(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String shape(String s) {
        String label = s.replaceFirst("^[A-Z]+:", "");
        if (s.startsWith("TABLE:")) return "[(\"" + label + "\")]";
        if (s.startsWith("BUILTIN:")) return "([\"" + label + " (Oracle)\"])";
        if (s.startsWith("EXTERNAL:") || s.startsWith("UNRESOLVED:")) return "{{\"" + label + " ?\"}}";
        return "[\"" + label + "\"]";
    }
}
