package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.analyze.RiskRules;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.StructureModel.*;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade.ParsedUnit;
import dev.sandeep.plsqlparser.parse.Preprocessor.Condition;

import java.util.*;

/**
 * Builds the {@link StructureModel} for a set of parsed files: structure (M2), then SQL access and call
 * resolution (M3), for primary variants plus routines that exist only in an alternate {@code $IF} variant.
 */
public final class StructureBuilder {
    private StructureBuilder() {}

    private record AltRef(StructureExtractor x, PlsqlUnit unit) {}

    public static StructureModel build(List<ParsedUnit> parsed) {
        StructureModel m = new StructureModel();
        List<StructureExtractor> primaries = new ArrayList<>(), alternates = new ArrayList<>();
        for (ParsedUnit pu : parsed) {
            String file = pu.path().toString().replace('\\', '/');
            long undecided = pu.conditions().stream().filter(c -> !c.decided()).count();
            m.files.add(new FileInfo(file, pu.variant(), pu.lines(), pu.errors().size(), (int) undecided,
                    pu.conditions().stream().filter(c -> !c.decided()).map(c -> "line " + c.line() + ": " + c.text()).distinct().toList()));
            pu.errors().stream().limit(10).forEach(e -> m.issues.add(new Issue("ERROR", "syntax-error",
                    "[" + pu.variant() + "] " + e.message(), file, e.line())));
            if (undecided > 0 && pu.variant().equals("primary"))
                m.issues.add(new Issue("INFO", "undecided-conditional-compilation",
                        pu.conditions().stream().filter(c -> !c.decided()).map(Condition::text).distinct().count()
                                + " distinct conditional-compilation conditions depend on unknown flags; both variants are analysed",
                        file, 0));
            StructureExtractor x = StructureExtractor.extract(pu);
            if (pu.variant().equals("primary")) {
                m.units.addAll(x.units);
                m.skipped.addAll(x.skipped);
                m.tableDefs.addAll(x.tableDefs);
                primaries.add(x);
            } else {
                alternates.add(x);
            }
        }
        Linker.link(m.units, m.issues);
        Map<Routine, AltRef> altRefs = diffAlternates(m, alternates);
        analyzeBodies(m, primaries, altRefs);
        return m;
    }

    /** M3: SQL access + calls for every unit/routine, then call resolution against everything we found. */
    private static void analyzeBodies(StructureModel m, List<StructureExtractor> primaries, Map<Routine, AltRef> altRefs) {
        Registry reg = Registry.build(m.units);
        CallResolver resolver = new CallResolver(reg);
        for (StructureExtractor x : primaries) {
            for (PlsqlUnit u : x.units) {
                BodyAnalyzer.analyzeUnit(x, u, reg);
                resolver.resolveUnit(u);
            }
        }
        for (StructureExtractor x : primaries) for (PlsqlUnit u : x.units) LogicAnalyzer.analyzeUnit(x, u, reg, m); // M4, needs resolved calls
        for (Map.Entry<Routine, AltRef> e : altRefs.entrySet()) {
            AltRef a = e.getValue();
            Set<String> none = new HashSet<>();
            BodyAnalyzer.analyzeRoutine(a.x(), e.getKey(), none, reg);
            resolver.resolveRoutine(a.unit(), e.getKey(), List.of());
            LogicAnalyzer.analyzeRoutine(a.x(), e.getKey(), Set.of(), Set.of(), m);
        }
        RiskRules.apply(m);
    }

    /** Routines present only in an alternate variant must not vanish: they are surfaced separately. */
    private static Map<Routine, AltRef> diffAlternates(StructureModel m, List<StructureExtractor> alternates) {
        Map<Routine, AltRef> refs = new IdentityHashMap<>();
        for (StructureExtractor alt : alternates) {
            for (PlsqlUnit au : alt.units) {
                Set<String> known = new HashSet<>();
                for (PlsqlUnit pu : m.units)
                    if (pu.file.equals(au.file) && pu.kind.equals(au.kind) && pu.key().equals(au.key()))
                        for (Routine r : pu.decls.routines) known.add(key(r));
                Map<String, Integer> seen = new HashMap<>();
                for (Routine r : au.decls.routines) {
                    if (known.contains(key(r))) continue;
                    String base = (au.name + "." + r.name).toUpperCase() + "~ALT";
                    int n = seen.merge(base, 1, Integer::sum);
                    r.id = n == 1 ? base : base + "#" + n;
                    m.alternateOnlyRoutines.add(r);
                    refs.put(r, new AltRef(alt, au));
                }
            }
        }
        return refs;
    }

    private static String key(Routine r) {
        return r.scope + "|" + r.forwardDeclaration + "|" + r.signatureKey();
    }
}
