package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.StructureModel.Issue;

import java.util.*;

/**
 * Links package specs to bodies (by name + parameter types, falling back to name + arity), marks routines
 * public/private, resolves overloads and assigns stable routine ids. Problems become {@link Issue}s.
 */
public final class Linker {
    private Linker() {}

    public static void link(List<PlsqlUnit> units, List<Issue> issues) {
        Map<String, PlsqlUnit> specs = new LinkedHashMap<>(), bodies = new LinkedHashMap<>();
        for (PlsqlUnit u : units) {
            if (u.kind.equals("PACKAGE_SPEC")) {
                if (specs.putIfAbsent(u.key(), u) != null)
                    issues.add(new Issue("WARN", "duplicate-package-spec", "package spec " + u.name + " defined more than once", u.file, u.line));
            } else if (u.kind.equals("PACKAGE_BODY")) {
                if (bodies.putIfAbsent(u.key(), u) != null)
                    issues.add(new Issue("WARN", "duplicate-package-body", "package body " + u.name + " defined more than once", u.file, u.line));
            } else if (u.kind.equals("PROCEDURE") || u.kind.equals("FUNCTION")) {
                for (Routine r : u.decls.routines) {
                    r.id = u.name.toUpperCase();
                    nestedIds(r);
                }
            }
        }
        Set<String> keys = new LinkedHashSet<>(specs.keySet());
        keys.addAll(bodies.keySet());
        for (String k : keys) {
            PlsqlUnit s = specs.get(k), b = bodies.get(k);
            if (s != null && b == null)
                issues.add(new Issue("WARN", "spec-without-body", "package spec " + s.name + " has no body in the scanned files", s.file, s.line));
            if (b != null && s == null)
                issues.add(new Issue("WARN", "body-without-spec",
                        "package body " + b.name + " has no spec in the scanned files (all routines treated as private)", b.file, b.line));
            if (b != null) linkBody(s, b, issues);
            assignIds(s, b);
        }
    }

    private static void linkBody(PlsqlUnit spec, PlsqlUnit body, List<Issue> issues) {
        List<Routine> defs = new ArrayList<>(), forwards = new ArrayList<>();
        for (Routine r : body.decls.routines) (r.forwardDeclaration ? forwards : defs).add(r);
        // forward declarations inside the body point at their definitions
        for (Routine f : forwards) {
            Routine def = find(defs, f, null);
            if (def != null) def.linkNote = "forward declared at line " + f.line;
            else issues.add(new Issue("WARN", "forward-without-definition", "forward declaration " + f.signature + " has no definition", f.file, f.line));
        }
        if (spec == null) {
            for (Routine r : defs) r.visibility = "UNKNOWN"; // spec not in scanned code: public/private cannot be decided
            return;
        }
        Set<Routine> used = new HashSet<>();
        for (Routine sr : spec.decls.routines) {
            Routine def = find(defs, sr, used);
            if (def == null) {
                if (sr.callSpec == null)
                    issues.add(new Issue("ERROR", "spec-routine-without-body",
                            "public " + sr.signature + " is declared in the spec but not implemented in the body", sr.file, sr.line));
                continue;
            }
            used.add(def);
            def.visibility = "PUBLIC";
            def.specLine = sr.line;
            sr.bodyDefLine = def.line;
            if (!def.signatureKey().equals(sr.signatureKey()))
                def.linkNote = "matched to spec by name+arity (parameter types differ textually)";
        }
    }

    /** Exact signature match first; otherwise a unique name+arity match among the not-yet-used candidates. */
    private static Routine find(List<Routine> defs, Routine want, Set<Routine> used) {
        for (Routine d : defs)
            if ((used == null || !used.contains(d)) && d.signatureKey().equals(want.signatureKey())) return d;
        Routine found = null;
        for (Routine d : defs) {
            if ((used != null && used.contains(d)) || !d.arityKey().equals(want.arityKey())) continue;
            if (found != null) return null; // ambiguous
            found = d;
        }
        return found;
    }

    private static void assignIds(PlsqlUnit spec, PlsqlUnit body) {
        String pkg = (body != null ? body : spec).name.toUpperCase();
        if (body != null) number(body.decls.routines, pkg);
        if (spec != null) {
            number(spec.decls.routines, pkg);
            if (body != null) {
                for (Routine sr : spec.decls.routines) {
                    if (sr.bodyDefLine == null) continue;
                    for (Routine br : body.decls.routines)
                        if (!br.forwardDeclaration && br.specLine != null && br.specLine == sr.line) sr.id = br.id;
                }
            }
        }
        if (body != null) for (Routine r : body.decls.routines) nestedIds(r);
        if (spec != null) for (Routine r : spec.decls.routines) nestedIds(r);
    }

    /** id = OWNER.NAME, with #n appended (order of appearance) when the name is overloaded. */
    private static void number(List<Routine> routines, String owner) {
        Map<String, Integer> total = new HashMap<>(), seen = new HashMap<>();
        for (Routine r : routines) if (!r.forwardDeclaration) total.merge(r.name.toLowerCase(), 1, Integer::sum);
        for (Routine r : routines) {
            if (r.forwardDeclaration) continue;
            String n = r.name.toLowerCase();
            int idx = seen.merge(n, 1, Integer::sum);
            r.id = owner + "." + r.name.toUpperCase() + (total.get(n) > 1 ? "#" + idx : "");
        }
        for (Routine r : routines) {
            if (!r.forwardDeclaration) continue;
            for (Routine d : routines)
                if (!d.forwardDeclaration && d.signatureKey().equals(r.signatureKey())) r.id = d.id;
            if (r.id == null) r.id = owner + "." + r.name.toUpperCase();
        }
    }

    private static void nestedIds(Routine parent) {
        String base = parent.id == null ? parent.name.toUpperCase() : parent.id;
        number(parent.locals.routines, base);
        for (Routine n : parent.locals.routines) {
            n.id = base + ">" + n.id.substring(base.length() + 1);
            nestedIds(n);
        }
    }
}
