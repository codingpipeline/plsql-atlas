package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.extract.Registry.PackageInfo;
import dev.sandeep.plsqlparser.model.*;

import java.util.*;

/** Resolves call sites to routines in the scanned code, Oracle built-ins, or marks them unresolved/external. */
public final class CallResolver {
    private final Registry reg;

    public CallResolver(Registry reg) {
        this.reg = reg;
    }

    /** Resolve every call in a unit (unit-level calls and all routines, recursively). */
    public void resolveUnit(PlsqlUnit u) {
        resolveList(u.calls, u, List.of());
        for (Routine r : u.decls.routines) resolveRoutine(u, r, List.of());
    }

    public void resolveRoutine(PlsqlUnit u, Routine r, List<Routine> enclosing) {
        List<Routine> chain = new ArrayList<>();
        chain.add(r);
        chain.addAll(enclosing);
        resolveList(r.calls, u, chain);
        for (Routine nested : r.locals.routines) resolveRoutine(u, nested, chain);
    }

    private void resolveList(List<CallSite> calls, PlsqlUnit unit, List<Routine> chain) {
        for (CallSite c : calls) resolve(c, unit, chain);
        // plain SQL/PLSQL standard functions (nvl, to_char...) are noise in a call graph; keep only raise_application_error
        calls.removeIf(c -> CallSite.BUILTIN.equals(c.resolution) && "STANDARD".equals(c.builtinPackage)
                && !c.callee.equalsIgnoreCase("raise_application_error"));
        // parameterless references that resolved to nothing are variables/constants/columns, not calls
        calls.removeIf(c -> !c.parens && !c.statement
                && (CallSite.UNRESOLVED.equals(c.resolution) || CallSite.EXTERNAL.equals(c.resolution)
                || CallSite.UNRESOLVED_IN_PACKAGE.equals(c.resolution)));
    }

    private void resolve(CallSite c, PlsqlUnit unit, List<Routine> chain) {
        if (c.callee.contains("@")) { c.resolution = CallSite.EXTERNAL; return; } // remote (db link) call
        String[] p = Arrays.stream(c.callee.split("\\.")).map(Src::ident).toArray(String[]::new);
        if (p.length == 1) {
            for (Routine scope : chain) {
                List<Routine> m = named(scope.locals.routines, p[0]);
                if (!m.isEmpty()) { pick(c, m, CallSite.LOCAL); return; }
            }
            PackageInfo pkg = unit.kind.startsWith("PACKAGE") ? reg.packages.get(unit.key()) : null;
            if (pkg != null) {
                List<Routine> m = pkg.named(p[0], false);
                if (!m.isEmpty()) { pick(c, m, CallSite.INTERNAL); return; }
            }
            List<Routine> sa = reg.standalone.get(p[0]);
            if (sa != null) { pick(c, sa, CallSite.INTERNAL); return; }
            if (OracleBuiltins.isFunction(p[0])) { c.resolution = CallSite.BUILTIN; c.builtinPackage = "STANDARD"; return; }
            if (isDeclaredType(p[0], chain)) { c.resolution = CallSite.BUILTIN; c.builtinPackage = "STANDARD"; return; } // collection/record constructor, not a call
            c.resolution = CallSite.UNRESOLVED;
            return;
        }
        String q = p[p.length - 2], n = p[p.length - 1];
        PackageInfo pkg = reg.packages.get(q);
        if (pkg != null) {
            boolean self = unit.kind.startsWith("PACKAGE") && unit.key().equals(q);
            List<Routine> m = pkg.named(n, !self);
            if (!m.isEmpty()) pick(c, m, CallSite.INTERNAL);
            else c.resolution = CallSite.UNRESOLVED_IN_PACKAGE;
        } else if (OracleBuiltins.isPackage(q) || p[0].equals("sys")) {
            c.resolution = CallSite.BUILTIN;
            c.builtinPackage = q.toUpperCase();
        } else if (p.length == 2 && reg.standalone.containsKey(n)) {
            pick(c, reg.standalone.get(n), CallSite.INTERNAL); // schema.routine
        } else {
            c.resolution = CallSite.EXTERNAL;
        }
    }

    /** A type declared in the scanned packages or in an enclosing routine: {@code t_list()} constructs a value, it is not a call. */
    private boolean isDeclaredType(String lower, List<Routine> chain) {
        if (reg.typeNames.contains(lower)) return true;
        for (Routine r : chain) for (var t : r.locals.types) if (t.name().equalsIgnoreCase(lower)) return true;
        return false;
    }

    private static List<Routine> named(List<Routine> routines, String lower) {
        List<Routine> out = new ArrayList<>();
        for (Routine r : routines) if (!r.forwardDeclaration && r.name.equalsIgnoreCase(lower)) out.add(r);
        return out;
    }

    /** Narrow overloads by argument count / names; one survivor becomes the target, several stay as candidates. */
    private static void pick(CallSite c, List<Routine> candidates, String resolution) {
        c.resolution = resolution;
        List<Routine> ok = new ArrayList<>();
        for (Routine r : candidates) if (compatible(c, r)) ok.add(r);
        List<Routine> use = ok.isEmpty() ? candidates : ok;
        if (use.size() == 1) c.targetId = use.get(0).id;
        else for (Routine r : use) c.candidates.add(r.id);
    }

    private static boolean compatible(CallSite c, Routine r) {
        long required = r.params.stream().filter(p -> p.defaultValue() == null).count();
        if (c.argCount > r.params.size() || c.argCount < required) return false;
        for (String named : c.namedArgs)
            if (r.params.stream().noneMatch(p -> p.name().equalsIgnoreCase(named))) return false;
        return true;
    }
}
