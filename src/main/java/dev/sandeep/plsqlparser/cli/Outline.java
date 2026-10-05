package dev.sandeep.plsqlparser.cli;

import dev.sandeep.plsqlparser.model.*;

import java.io.PrintStream;

/** Human-readable text outline of a {@link StructureModel} (used by the {@code outline} command). */
final class Outline {
    private Outline() {}

    static boolean logic;

    static void print(StructureModel m, PrintStream out, boolean verbose) {
        for (PlsqlUnit u : m.units) {
            out.printf("%s %s  (%s:%d-%d)%s%n", u.kind, u.qualifiedName(), shortName(u.file), u.line, u.endLine,
                    u.authid == null ? "" : "  " + u.authid);
            if (u.triggerHeader != null) out.println("  header: " + u.triggerHeader);
            decls(u.decls, "  ", out, verbose);
            if (u.hasInitBlock) out.println("  [init block @" + u.initBlockLine + "]");
        }
        if (!m.alternateOnlyRoutines.isEmpty()) {
            out.println("\nRoutines found ONLY in alternate $IF variant:");
            for (Routine r : m.alternateOnlyRoutines) out.printf("  %s  (line %d)%n", r.signature, r.line);
        }
        if (!m.skipped.isEmpty()) {
            out.println("\nNot decomposed (listed, not dropped):");
            m.skipped.stream().limit(verbose ? Integer.MAX_VALUE : 15).forEach(s ->
                    out.printf("  %s:%d-%d  %s  %s%n", shortName(s.file()), s.line(), s.endLine(), s.kind(), s.name()));
            if (!verbose && m.skipped.size() > 15) out.println("  ... " + (m.skipped.size() - 15) + " more (use -v)");
        }
        if (!m.issues.isEmpty()) {
            out.println("\nIssues:");
            m.issues.forEach(i -> out.printf("  [%s] %s  %s:%d  %s%n", i.severity(), i.code(), shortName(i.file()), i.line(), i.message()));
        }
    }

    private static void decls(Declarations d, String ind, PrintStream out, boolean verbose) {
        out.printf("%svars=%d consts=%d types=%d cursors=%d exceptions=%d pragmas=%d routines=%d%n", ind,
                d.variables.stream().filter(v -> !v.constant()).count(), d.variables.stream().filter(Declarations.Variable::constant).count(),
                d.types.size(), d.cursors.size(), d.exceptions.size(), d.pragmas.size(), d.routines.size());
        if (verbose) {
            d.types.forEach(t -> out.printf("%s  type %s [%s]%n", ind, t.name(), t.kind()));
            d.cursors.forEach(c -> out.printf("%s  cursor %s(%d params)%n", ind, c.name(), c.params().size()));
            d.exceptions.forEach(e -> out.printf("%s  exception %s%s%n", ind, e.name(), e.errorCode() == null ? "" : " = " + e.errorCode()));
        }
        for (Routine r : d.routines) {
            out.printf("%s- %-7s %-30s %s%s%s%n", ind, r.visibility, r.id, r.signature,
                    r.attributes.isEmpty() ? "" : "  " + r.attributes,
                    r.forwardDeclaration ? "  [forward]" : r.hasBody ? "" : "  [decl only]");
            if (verbose) {
                out.printf("%s    lines %d-%d%s%s%n", ind, r.line, r.endLine, r.specLine == null ? "" : ", spec@" + r.specLine,
                        r.linkNote == null ? "" : "  (" + r.linkNote + ")");
                for (SqlStatement s : r.sql) {
                    StringBuilder t = new StringBuilder();
                    for (SqlStatement.TableRef tr : s.tables)
                        t.append(' ').append(tr.key()).append(tr.access).append(tr.readColumns.isEmpty() ? "" : " r=" + tr.readColumns)
                                .append(tr.writeColumns.isEmpty() ? "" : " w=" + tr.writeColumns).append(tr.confidence.equals("EXACT") ? "" : " (inferred)");
                    out.printf("%s    sql@%d %s%s%s%s binds=%s%n", ind, s.line, s.kind, s.inLoop ? " [IN LOOP]" : "",
                            s.dynamic ? " [DYNAMIC " + s.dynamicConfidence + "]" : "", t, s.binds);
                }
                for (CallSite c : r.calls)
                    out.printf("%s    call@%d %s -> %s%s%n", ind, c.line, c.callee, c.resolution,
                            c.targetId != null ? " " + c.targetId : !c.candidates.isEmpty() ? " ?" + c.candidates : c.builtinPackage != null ? " " + c.builtinPackage : "");
                if (!r.docComments.isEmpty()) out.printf("%s    doc: %s%n", ind, first(r.docComments.get(0)));
                if (!r.bodyHeaderComments.isEmpty()) out.printf("%s    header: %s%n", ind, first(r.bodyHeaderComments.get(0)));
            }
            if (logic) logic(r, ind + "    ", out);
            if (!r.locals.routines.isEmpty() || verbose && hasAny(r.locals)) decls(r.locals, ind + "    ", out, verbose);
        }
    }

    /** Numbered logic outline + contract + effects + metrics of one routine. */
    static void logic(Routine r, String ind, PrintStream out) {
        if (!r.hasBody) return;
        Metrics mx = r.metrics;
        out.printf("%smetrics: loc=%d statements=%d cyclomatic=%d nesting=%d loops=%d sql=%d calls=%d%n", ind, mx.linesOfCode,
                mx.statements, mx.cyclomatic, mx.maxNesting, mx.loops, mx.sqlStatements, mx.calls);
        steps(r.outline, ind + "  ", "", out);
        ExceptionContract ec = r.exceptionContract;
        if (!ec.raises.isEmpty() || !ec.handlers.isEmpty() || !ec.implicit.isEmpty()) {
            out.printf("%sexceptions: implicit=%s%n", ind, ec.implicit);
            ec.raises.forEach(x -> out.printf("%s  raises %s %s%s @%d%n", ind, x.how(), x.exception() == null ? "" : x.exception(),
                    x.errorCode() != null ? " code " + x.errorCode() : x.codeExpression() != null ? " code " + x.codeExpression() : "", x.line()));
            ec.handlers.forEach(h -> out.printf("%s  handles %s @%d%s%s%n", ind, h.exceptions(), h.line(),
                    h.reraises() ? " (re-raises)" : " (swallows)", h.others() ? " [OTHERS]" : ""));
        }
        SideEffects fx = r.sideEffects;
        StringBuilder b = new StringBuilder();
        if (!fx.commits.isEmpty()) b.append(" commits@").append(fx.commits);
        if (!fx.rollbacks.isEmpty()) b.append(" rollbacks@").append(fx.rollbacks);
        if (fx.autonomousTransaction) b.append(" AUTONOMOUS_TXN");
        if (!fx.packageStateWrites.isEmpty()) b.append(" writes-state=").append(fx.packageStateWrites);
        if (!fx.packageStateReads.isEmpty()) b.append(" reads-state=").append(fx.packageStateReads);
        if (!fx.sequences.isEmpty()) b.append(" sequences=").append(fx.sequences);
        if (!fx.ddl.isEmpty()) b.append(" ddl=").append(fx.ddl);
        if (!fx.locks.isEmpty()) b.append(" locks=").append(fx.locks);
        if (!fx.dbLinks.isEmpty()) b.append(" dblinks=").append(fx.dbLinks);
        if (!fx.externalIo.isEmpty()) b.append(" io=").append(fx.externalIo);
        if (b.length() > 0) out.printf("%seffects:%s%n", ind, b);
        for (Risk k : r.risks) out.printf("%s! [%s] %s @%d %s%n", ind, k.severity(), k.code(), k.line(), k.message());
    }

    private static void steps(java.util.List<LogicStep> list, String ind, String prefix, PrintStream out) {
        int i = 1;
        for (LogicStep s : list) {
            String num = prefix + i++;
            out.printf("%s%-8s %-14s %s%s%s%n", ind, num, s.kind, s.label == null ? "" : "<<" + s.label + ">> ",
                    s.text == null ? "" : first(s.text), s.refs.isEmpty() ? "" : "   {" + String.join("; ", s.refs) + "}");
            steps(s.children, ind, num + ".", out);
        }
    }

    private static boolean hasAny(Declarations d) {
        return !d.variables.isEmpty() || !d.cursors.isEmpty() || !d.types.isEmpty() || !d.exceptions.isEmpty() || !d.routines.isEmpty();
    }

    private static String first(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 100 ? t.substring(0, 100) + "..." : t;
    }

    private static String shortName(String f) {
        return f.substring(f.lastIndexOf('/') + 1);
    }
}
