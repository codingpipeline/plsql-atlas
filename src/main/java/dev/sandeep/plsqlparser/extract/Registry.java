package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.model.PlsqlUnit;
import dev.sandeep.plsqlparser.model.Routine;

import java.util.*;

/** Index of everything callable that was found in the scanned code. */
public final class Registry {
    /** A package as seen across its spec and body. */
    public record PackageInfo(PlsqlUnit spec, PlsqlUnit body) {
        /** Implemented routines named {@code lowerName} (body definitions first, then spec-only declarations). */
        public List<Routine> named(String lowerName, boolean publicOnly) {
            List<Routine> out = new ArrayList<>();
            if (body != null)
                for (Routine r : body.decls.routines)
                    if (!r.forwardDeclaration && r.name.toLowerCase().equals(lowerName) && (!publicOnly || spec == null || "PUBLIC".equals(r.visibility)))
                        out.add(r);
            if (spec != null)
                for (Routine r : spec.decls.routines)
                    if (r.name.toLowerCase().equals(lowerName) && r.bodyDefLine == null) out.add(r);
            return out;
        }
    }

    public final Map<String, PackageInfo> packages = new HashMap<>();
    public final Map<String, List<Routine>> standalone = new HashMap<>();
    public final Set<String> routineNames = new HashSet<>();
    public final Set<String> typeNames = new HashSet<>();

    public static Registry build(List<PlsqlUnit> units) {
        Registry reg = new Registry();
        Map<String, PlsqlUnit> specs = new HashMap<>(), bodies = new HashMap<>();
        for (PlsqlUnit u : units) {
            u.decls.types.forEach(t -> reg.typeNames.add(t.name().toLowerCase()));
            switch (u.kind) {
                case "PACKAGE_SPEC" -> specs.putIfAbsent(u.key(), u);
                case "PACKAGE_BODY" -> bodies.putIfAbsent(u.key(), u);
                case "PROCEDURE", "FUNCTION" -> {
                    for (Routine r : u.decls.routines) {
                        reg.standalone.computeIfAbsent(r.name.toLowerCase(), k -> new ArrayList<>()).add(r);
                        reg.routineNames.add(r.name.toLowerCase());
                    }
                }
                default -> { }
            }
        }
        Set<String> keys = new HashSet<>(specs.keySet());
        keys.addAll(bodies.keySet());
        for (String k : keys) {
            PackageInfo p = new PackageInfo(specs.get(k), bodies.get(k));
            reg.packages.put(k, p);
            for (PlsqlUnit u : new PlsqlUnit[]{p.spec(), p.body()}) {
                if (u == null) continue;
                collectNames(u.decls.routines, reg.routineNames);
            }
        }
        return reg;
    }

    private static void collectNames(List<Routine> routines, Set<String> into) {
        for (Routine r : routines) {
            into.add(r.name.toLowerCase());
            collectNames(r.locals.routines, into);
        }
    }
}
