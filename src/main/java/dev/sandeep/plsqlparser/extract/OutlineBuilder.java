package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.ExceptionContract.Handler;
import dev.sandeep.plsqlparser.model.ExceptionContract.Raise;
import dev.sandeep.plsqlparser.model.SqlStatement.TableRef;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * M4: turns the statements of one body into a lossless control-flow outline ({@link LogicStep} tree) and, on the
 * way, collects the exception contract, transaction-control effects and complexity counters.
 * Every {@code statement} parse node must become exactly one step; {@link #problems} reports any mismatch.
 */
final class OutlineBuilder {
    private final List<SqlStatement> sql;
    private final List<CallSite> calls;
    private final Set<SqlStatement> claimedSql = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CallSite> claimedCalls = Collections.newSetFromMap(new IdentityHashMap<>());

    final ExceptionContract ec = new ExceptionContract();
    final SideEffects fx = new SideEffects();
    final Metrics mx = new Metrics();
    final List<String> problems = new ArrayList<>();
    private int statementSteps;
    private int depth;

    OutlineBuilder(List<SqlStatement> sql, List<CallSite> calls) {
        this.sql = sql;
        this.calls = calls;
    }

    // ------------------------------------------------------------------ entry points

    /** A routine body: statements, then its EXCEPTION section. */
    List<LogicStep> buildBody(BodyContext body) {
        List<LogicStep> out = new ArrayList<>();
        if (body.seq_of_statements() != null) seq(body.seq_of_statements(), out);
        exceptionSection(body.exception_handler(), out);
        return finish(out, body);
    }

    /** Any container (package init block, trigger body, anonymous block): the top-most statement lists and handlers in it. */
    List<LogicStep> buildContainers(List<? extends ParserRuleContext> roots) {
        List<LogicStep> out = new ArrayList<>();
        List<Exception_handlerContext> handlers = new ArrayList<>();
        for (ParserRuleContext r : roots) topmost(r, out, handlers);
        exceptionSection(handlers, out);
        return finish(out, roots.toArray(new ParserRuleContext[0]));
    }

    private void topmost(ParseTree n, List<LogicStep> out, List<Exception_handlerContext> handlers) {
        if (n instanceof Seq_of_statementsContext s) { seq(s, out); return; }
        if (n instanceof Exception_handlerContext h) { handlers.add(h); return; }
        if (n instanceof Procedure_bodyContext || n instanceof Function_bodyContext) return; // nested routine declarations
        for (int i = 0; i < n.getChildCount(); i++) topmost(n.getChild(i), out, handlers);
    }

    private List<LogicStep> finish(List<LogicStep> out, ParserRuleContext... roots) {
        int nodes = 0;
        for (ParserRuleContext r : roots) nodes += countStatements(r);
        if (nodes != statementSteps)
            problems.add("outline has " + statementSteps + " statement steps but the parse tree has " + nodes + " statement nodes");
        mx.statements = statementSteps;
        mx.cyclomatic = 1 + mx.decisionPoints;
        mx.sqlStatements = sql.size();
        mx.calls = calls.size();
        return out;
    }

    private static int countStatements(ParseTree n) {
        if (n instanceof Procedure_bodyContext || n instanceof Function_bodyContext) return 0;
        int c = n instanceof StatementContext ? 1 : 0;
        for (int i = 0; i < n.getChildCount(); i++) c += countStatements(n.getChild(i));
        return c;
    }

    // ------------------------------------------------------------------ statements

    private void seq(Seq_of_statementsContext s, List<LogicStep> out) {
        for (int i = 0; i < s.getChildCount(); i++) {
            ParseTree ch = s.getChild(i);
            if (ch instanceof StatementContext st) statement(st, out);
            else if (ch instanceof Label_declarationContext l)
                out.add(new LogicStep("LABEL", "<<" + Src.norm(Src.text(l.label_name())) + ">>", l.getStart().getLine(), Src.endLine(l)));
            else if (ch instanceof Pragma_declarationContext p)
                out.add(new LogicStep("PRAGMA", Src.norm(Src.text(p)), p.getStart().getLine(), Src.endLine(p)));
            else if (ch instanceof Selection_directiveContext d) {
                out.add(new LogicStep("CONDITIONAL_COMPILATION", Src.clip(Src.norm(Src.text(d)), 200), d.getStart().getLine(), Src.endLine(d)));
                problems.add("unresolved conditional compilation inside statements at line " + d.getStart().getLine());
            }
        }
    }

    private void statement(StatementContext st, List<LogicStep> out) {
        statementSteps++;
        ParseTree c = st.getChild(0);
        LogicStep step;
        if (c instanceof BodyContext b) step = block(null, b, st);
        else if (c instanceof BlockContext b) step = block(b.declare_spec(), b.body(), st);
        else if (c instanceof If_statementContext i) step = ifStatement(i);
        else if (c instanceof Loop_statementContext l) step = loop(l);
        else if (c instanceof Forall_statementContext f) step = forall(f);
        else if (c instanceof Case_statementContext cs) step = caseStatement(cs);
        else if (c instanceof Assignment_statementContext a) step = simple("ASSIGN", a, a, 300);
        else if (c instanceof Exit_statementContext e) step = simple("EXIT", e, e, 200);
        else if (c instanceof Continue_statementContext e) step = simple("CONTINUE", e, e, 200);
        else if (c instanceof Goto_statementContext g) {
            step = simple("GOTO", g, g, 100);
            mx.decisionPoints++;
        } else if (c instanceof Null_statementContext n) step = simple("NULL", n, n, 20);
        else if (c instanceof Raise_statementContext r) step = raise(r);
        else if (c instanceof Return_statementContext r) step = simple("RETURN", r, r, 300);
        else if (c instanceof Call_statementContext call) step = call(call);
        else if (c instanceof Pipe_row_statementContext p) step = simple("PIPE_ROW", p, p, 200);
        else if (c instanceof Grant_statementContext g) step = simple("GRANT", g, g, 200);
        else if (c instanceof Sql_statementContext s) step = sqlStatement(s);
        else {
            ParserRuleContext pc = (ParserRuleContext) c;
            step = simple("UNKNOWN", pc, pc, 200);
            problems.add("unmodelled statement kind " + Src.rule(pc) + " at line " + pc.getStart().getLine());
        }
        out.add(step);
    }

    private LogicStep block(List<Declare_specContext> decls, BodyContext body, ParserRuleContext whole) {
        LogicStep s = new LogicStep("BLOCK", null, whole.getStart().getLine(), Src.endLine(whole));
        if (decls != null && !decls.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Declare_specContext d : decls) names.add(Src.clip(Src.norm(Src.text(d)), 60));
            s.text = "DECLARE " + String.join(" ", names);
        }
        if (body.label_name() != null) s.label = Src.text(body.label_name());
        depth++;
        mx.maxNesting = Math.max(mx.maxNesting, depth);
        if (body.seq_of_statements() != null) seq(body.seq_of_statements(), s.children);
        exceptionSection(body.exception_handler(), s.children);
        depth--;
        link(s, start(whole), start(whole));
        return s;
    }

    private LogicStep ifStatement(If_statementContext i) {
        LogicStep s = new LogicStep("IF", Src.norm(Src.text(i.condition())), i.getStart().getLine(), Src.endLine(i));
        mx.decisionPoints++;
        depth++;
        mx.maxNesting = Math.max(mx.maxNesting, depth);
        LogicStep then = new LogicStep("THEN", null, i.condition().getStop().getLine(), Src.endLine(i.seq_of_statements()));
        seq(i.seq_of_statements(), then.children);
        s.children.add(then);
        for (Elsif_partContext e : i.elsif_part()) {
            mx.decisionPoints++;
            LogicStep es = new LogicStep("ELSIF", Src.norm(Src.text(e.condition())), e.getStart().getLine(), Src.endLine(e));
            seq(e.seq_of_statements(), es.children);
            s.children.add(es);
        }
        if (i.else_part() != null) {
            LogicStep el = new LogicStep("ELSE", null, i.else_part().getStart().getLine(), Src.endLine(i.else_part()));
            seq(i.else_part().seq_of_statements(), el.children);
            s.children.add(el);
        }
        depth--;
        link(s, start(i), stop(i.condition()));
        return s;
    }

    private LogicStep caseStatement(Case_statementContext cs) {
        List<Case_when_part_statementContext> whens;
        Case_else_part_statementContext els;
        String text;
        ParserRuleContext whole;
        if (cs.simple_case_statement() != null) {
            var sc = cs.simple_case_statement();
            whens = sc.case_when_part_statement();
            els = sc.case_else_part_statement();
            text = Src.norm(Src.text(sc.expression()));
            whole = sc;
        } else {
            var sc = cs.searched_case_statement();
            whens = sc.case_when_part_statement();
            els = sc.case_else_part_statement();
            text = "(searched)";
            whole = sc;
        }
        LogicStep s = new LogicStep("CASE", text, whole.getStart().getLine(), Src.endLine(whole));
        depth++;
        mx.maxNesting = Math.max(mx.maxNesting, depth);
        for (Case_when_part_statementContext w : whens) {
            mx.decisionPoints++;
            LogicStep ws = new LogicStep("WHEN", Src.norm(Src.text(w.expression())), w.getStart().getLine(), Src.endLine(w));
            seq(w.seq_of_statements(), ws.children);
            s.children.add(ws);
        }
        if (els != null) {
            LogicStep es = new LogicStep("ELSE", null, els.getStart().getLine(), Src.endLine(els));
            seq(els.seq_of_statements(), es.children);
            s.children.add(es);
        }
        depth--;
        link(s, start(whole), cs.simple_case_statement() != null ? stop(cs.simple_case_statement().expression()) : start(whole));
        return s;
    }

    private LogicStep loop(Loop_statementContext l) {
        String kind = "LOOP", text = null;
        int headEnd = l.getStart().getStopIndex();
        if (l.condition() != null) {
            kind = "WHILE_LOOP";
            text = Src.norm(Src.text(l.condition()));
            headEnd = stop(l.condition());
        } else if (l.cursor_loop_param() != null) {
            Cursor_loop_paramContext p = l.cursor_loop_param();
            kind = p.index_name() != null ? "FOR_LOOP" : "CURSOR_FOR_LOOP";
            text = Src.clip(Src.norm(Src.text(p)), 300);
            headEnd = stop(p);
        }
        LogicStep s = new LogicStep(kind, text, l.getStart().getLine(), Src.endLine(l));
        if (l.label_name() != null) s.label = Src.text(l.label_name());
        else if (l.label_declaration() != null) s.label = Src.text(l.label_declaration().label_name());
        mx.loops++;
        mx.decisionPoints++;
        depth++;
        mx.maxNesting = Math.max(mx.maxNesting, depth);
        seq(l.seq_of_statements(), s.children);
        depth--;
        link(s, start(l), headEnd);
        return s;
    }

    private LogicStep forall(Forall_statementContext f) {
        LogicStep s = new LogicStep("FORALL", Src.norm(Src.text(f.index_name())) + " IN " + Src.norm(Src.text(f.bounds_clause())),
                f.getStart().getLine(), Src.endLine(f));
        mx.loops++;
        mx.decisionPoints++;
        ParserRuleContext inner = f.data_manipulation_language_statements() != null ? f.data_manipulation_language_statements() : f.execute_immediate();
        LogicStep child = new LogicStep(f.execute_immediate() != null ? "DYNAMIC_SQL" : "SQL", Src.clip(Src.norm(Src.text(inner)), 400),
                inner.getStart().getLine(), Src.endLine(inner));
        link(child, start(inner), stop(inner));
        s.children.add(child);
        link(s, start(f), stop(f.bounds_clause()));
        return s;
    }

    private LogicStep raise(Raise_statementContext r) {
        LogicStep s = simple("RAISE", r, r, 100);
        if (r.exception_name() != null) ec.raises.add(new Raise(Src.norm(Src.text(r.exception_name())), "RAISE", null, null, r.getStart().getLine()));
        else ec.raises.add(new Raise(null, "RE_RAISE", null, null, r.getStart().getLine()));
        return s;
    }

    private LogicStep call(Call_statementContext c) {
        LogicStep s = simple("CALL", c, c, 300);
        if (!c.routine_name().isEmpty() && Src.norm(Src.text(c.routine_name(0))).equalsIgnoreCase("raise_application_error")) {
            String expr = null;
            Integer code = null;
            if (!c.function_argument().isEmpty() && !c.function_argument(0).argument().isEmpty()) {
                expr = Src.norm(Src.text(c.function_argument(0).argument(0).expression()));
                try { code = Integer.parseInt(expr.replace(" ", "")); } catch (NumberFormatException ignored) { /* constant or expression */ }
            }
            ec.raises.add(new Raise("RAISE_APPLICATION_ERROR", "RAISE_APPLICATION_ERROR", code, expr, c.getStart().getLine()));
        }
        return s;
    }

    private LogicStep sqlStatement(Sql_statementContext s) {
        ParseTree c = s.getChild(0);
        ParserRuleContext pc = (ParserRuleContext) c;
        if (c instanceof Execute_immediateContext) return simple("DYNAMIC_SQL", s, s, 400);
        if (c instanceof Data_manipulation_language_statementsContext) return simple("SQL", s, s, 400);
        if (c instanceof Collection_method_callContext) return simple("COLLECTION_OP", s, s, 200);
        if (c instanceof Cursor_manipulation_statementsContext cm) {
            ParseTree k = cm.getChild(0);
            String kind = k instanceof Open_for_statementContext ? "OPEN_FOR" : k instanceof Open_statementContext ? "OPEN_CURSOR"
                    : k instanceof Fetch_statementContext ? "FETCH" : "CLOSE_CURSOR";
            return simple(kind, s, s, 300);
        }
        if (c instanceof Transaction_control_statementsContext tc) {
            ParseTree k = tc.getChild(0);
            int line = tc.getStart().getLine();
            String kind;
            if (k instanceof Commit_statementContext) { kind = "COMMIT"; fx.commits.add(line); }
            else if (k instanceof Rollback_statementContext) { kind = "ROLLBACK"; fx.rollbacks.add(line); }
            else if (k instanceof Savepoint_statementContext) { kind = "SAVEPOINT"; fx.savepoints.add(line); }
            else { kind = "SET_TRANSACTION"; fx.setTransaction = true; }
            return simple(kind, s, s, 200);
        }
        problems.add("unmodelled sql_statement " + Src.rule(pc) + " at line " + pc.getStart().getLine());
        return simple("UNKNOWN", s, s, 200);
    }

    private LogicStep simple(String kind, ParserRuleContext textCtx, ParserRuleContext span, int clip) {
        LogicStep s = new LogicStep(kind, Src.clip(Src.norm(Src.text(textCtx)), clip), span.getStart().getLine(), Src.endLine(span));
        link(s, start(span), stop(span));
        return s;
    }

    // ------------------------------------------------------------------ exception handlers

    private void exceptionSection(List<Exception_handlerContext> handlers, List<LogicStep> out) {
        if (handlers.isEmpty()) return;
        LogicStep sec = new LogicStep("EXCEPTION_SECTION", null, handlers.get(0).getStart().getLine(),
                Src.endLine(handlers.get(handlers.size() - 1)));
        for (Exception_handlerContext h : handlers) {
            List<String> names = new ArrayList<>();
            for (Exception_nameContext n : h.exception_name()) names.add(Src.norm(Src.text(n)));
            LogicStep hs = new LogicStep("HANDLER", "WHEN " + String.join(" OR ", names), h.getStart().getLine(), Src.endLine(h));
            mx.decisionPoints++;
            depth++;
            mx.maxNesting = Math.max(mx.maxNesting, depth);
            seq(h.seq_of_statements(), hs.children);
            depth--;
            sec.children.add(hs);
            List<LogicStep> flat = new ArrayList<>();
            flatten(hs.children, flat);
            boolean reraises = flat.stream().anyMatch(x -> x.kind.equals("RAISE") || x.kind.equals("CALL") && x.text.toLowerCase().startsWith("raise_application_error"));
            boolean onlyNull = !flat.isEmpty() && flat.stream().allMatch(x -> x.kind.equals("NULL"));
            ec.handlers.add(new Handler(names, h.getStart().getLine(), Src.endLine(h),
                    names.stream().anyMatch(n -> n.equalsIgnoreCase("others")), reraises, !reraises, onlyNull,
                    flat.stream().anyMatch(x -> x.kind.equals("CALL") || x.kind.equals("SQL")),
                    flat.stream().anyMatch(x -> x.kind.equals("RETURN")),
                    flat.stream().anyMatch(x -> x.kind.equals("COMMIT") || x.kind.equals("ROLLBACK"))));
        }
        out.add(sec);
    }

    private static void flatten(List<LogicStep> in, List<LogicStep> out) {
        for (LogicStep s : in) {
            out.add(s);
            flatten(s.children, out);
        }
    }

    // ------------------------------------------------------------------ linking steps to SQL and calls

    /** Attach SQL statements and calls whose start lies in [from, to] (character offsets) and are not yet claimed by a child step. */
    private void link(LogicStep s, int from, int to) {
        for (SqlStatement q : sql) {
            if (q.offset < from || q.offset > to || !claimedSql.add(q)) continue;
            if (q.dynamic) s.refs.add("dynamic SQL " + q.dynamicConfidence + (q.dynamicKind == null ? "" : " " + q.dynamicKind));
            for (TableRef t : q.tables) s.refs.add(String.join("/", t.access) + " " + t.key() + ("INFERRED".equals(t.confidence) ? " (inferred)" : ""));
        }
        for (CallSite c : calls) {
            if (c.offset < from || c.offset > to || !claimedCalls.add(c)) continue;
            s.refs.add("calls " + describe(c));
        }
    }

    private static int start(ParserRuleContext c) {
        return c.getStart().getStartIndex();
    }

    private static int stop(ParserRuleContext c) {
        return c.getStop() == null ? c.getStart().getStopIndex() : c.getStop().getStopIndex();
    }

    static String describe(CallSite c) {
        if (c.targetId != null) return c.targetId;
        if (!c.candidates.isEmpty()) return c.callee + " ?" + c.candidates;
        if (CallSite.BUILTIN.equals(c.resolution)) return c.callee + " (Oracle " + c.builtinPackage + ")";
        if (CallSite.EXTERNAL.equals(c.resolution) || CallSite.UNRESOLVED_IN_PACKAGE.equals(c.resolution)) return c.callee + " (external)";
        return c.callee + " (unresolved)";
    }
}
