package dev.sandeep.plsqlparser.extract;

import dev.sandeep.plsqlparser.grammar.PlSqlLexer;
import dev.sandeep.plsqlparser.grammar.PlSqlParser;
import dev.sandeep.plsqlparser.grammar.PlSqlParser.*;
import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.Declarations.*;
import dev.sandeep.plsqlparser.model.StructureModel.SkippedItem;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade.ParsedUnit;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.*;

import java.util.*;

/**
 * M2: walks the ANTLR parse tree of one parsed file (variant) and extracts its structure: units, routines,
 * parameters, variables, types, cursors, exceptions, pragmas and nested subprograms.
 * Everything that is not modelled is returned in {@link #skipped} so nothing disappears silently.
 */
public final class StructureExtractor {
    private final ParsedUnit pu;
    private final String file;
    private final CommonTokenStream tokens;
    public final List<PlsqlUnit> units = new ArrayList<>();
    public final List<SkippedItem> skipped = new ArrayList<>();
    public final List<TableDef> tableDefs = new ArrayList<>();

    /** Parse-tree handles kept so later passes (SQL, calls, logic) can walk routine bodies. */
    public record RoutineCtx(ParserRuleContext whole, BodyContext body, Seq_of_declare_specsContext decls) {}
    public final Map<Routine, RoutineCtx> routineCtx = new IdentityHashMap<>();
    public final Map<Routine, Routine> parent = new IdentityHashMap<>();
    /** Statement containers outside routines: package init block, trigger body, anonymous block. */
    public final Map<PlsqlUnit, List<ParserRuleContext>> unitBodies = new IdentityHashMap<>();
    public final Map<Declarations, List<Cursor_declarationContext>> cursorCtx = new IdentityHashMap<>();
    /** Default-value / initialiser expressions (variables, parameters): calls inside them are real calls. */
    public final Map<Declarations, List<ParserRuleContext>> initCtx = new IdentityHashMap<>();
    public final Map<Routine, List<ParserRuleContext>> paramDefaultCtx = new IdentityHashMap<>();

    private StructureExtractor(ParsedUnit pu) {
        this.pu = pu;
        this.file = pu.path().toString().replace('\\', '/');
        this.tokens = pu.tokens();
    }

    public static StructureExtractor extract(ParsedUnit pu) {
        StructureExtractor x = new StructureExtractor(pu);
        x.run();
        return x;
    }

    // ------------------------------------------------------------------ top level

    private void run() {
        for (Script_unitContext su : pu.tree().script_unit()) {
            Plsql_unitContext pl = su.plsql_unit();
            if (pl != null) {
                if (pl.create_package() != null) packageSpec(pl.create_package());
                else if (pl.create_package_body() != null) packageBody(pl.create_package_body());
                else if (pl.create_procedure_body() != null) standaloneProcedure(pl.create_procedure_body());
                else if (pl.create_function_body() != null) standaloneFunction(pl.create_function_body());
                else if (pl.create_trigger() != null) trigger(pl.create_trigger());
                else if (pl.anonymous_block() != null) anonymousBlock(pl.anonymous_block());
                else if (pl.create_type_body() != null) skip(pl.create_type_body(), "create_type_body", "object type body");
                else skip(pl, "plsql_unit", "unrecognised PL/SQL unit");
            } else if (su.sql_unit() != null) {
                ParserRuleContext child = (ParserRuleContext) su.sql_unit().getChild(0);
                skip(su.sql_unit(), ruleName(child), "DDL/DML statement (tables, views, grants...) — not PL/SQL structure");
                TableDef def = child instanceof Create_tableContext ct ? DdlExtractor.createTable(ct, file)
                        : child instanceof Alter_tableContext at ? DdlExtractor.alterTable(at, file) : null;
                if (def != null) tableDefs.add(def);
            } else if (su.sql_plus_command() != null) {
                skip(su.sql_plus_command(), "sql_plus_command", "SQL*Plus command");
            }
        }
    }

    private void skip(ParserRuleContext c, String kind, String reason) {
        String name = norm(text(c));
        if (name.length() > 80) name = name.substring(0, 80) + "...";
        skipped.add(new SkippedItem(file, pu.variant(), kind, name, c.getStart().getLine(), endLine(c), reason));
    }

    private PlsqlUnit newUnit(String kind, ParserRuleContext ctx, String schema, String name) {
        PlsqlUnit u = new PlsqlUnit();
        u.kind = kind;
        u.schema = schema;
        u.name = name;
        u.file = file;
        u.variant = pu.variant();
        u.line = ctx.getStart().getLine();
        u.endLine = endLine(ctx);
        u.docComments.addAll(commentsLeft(ctx.getStart()));
        units.add(u);
        return u;
    }

    private void packageSpec(Create_packageContext c) {
        PlsqlUnit u = newUnit("PACKAGE_SPEC", c, c.schema_object_name() == null ? null : text(c.schema_object_name()),
                text(c.package_name(0)));
        if (c.invoker_rights_clause() != null) u.authid = norm(text(c.invoker_rights_clause()));
        collect(c.package_obj_spec(), u.decls, "PACKAGE_SPEC", u.name, "PUBLIC");
    }

    private void packageBody(Create_package_bodyContext c) {
        PlsqlUnit u = newUnit("PACKAGE_BODY", c, c.schema_object_name() == null ? null : text(c.schema_object_name()),
                text(c.package_name(0)));
        collect(c.package_obj_body(), u.decls, "PACKAGE_BODY", u.name, "PRIVATE");
        if (c.BEGIN() != null) {
            u.hasInitBlock = true;
            u.initBlockLine = c.BEGIN().getSymbol().getLine();
            List<ParserRuleContext> blocks = new ArrayList<>();
            if (c.seq_of_statements() != null) blocks.add(c.seq_of_statements());
            blocks.addAll(c.exception_handler());
            unitBodies.put(u, blocks);
        }
    }

    private void standaloneProcedure(Create_procedure_bodyContext c) {
        Procedure_nameContext n = c.procedure_name();
        boolean q = n.PERIOD() != null;
        PlsqlUnit u = newUnit("PROCEDURE", c, q ? text(n.identifier()) : null, q ? text(n.id_expression()) : text(n.identifier()));
        if (c.invoker_rights_clause() != null) u.authid = norm(text(c.invoker_rights_clause()));
        Routine r = routine(c, "PROCEDURE", u.name, c.parameter(), null, "STANDALONE", null, "STANDALONE",
                c.body(), c.seq_of_declare_specs(), c.call_spec());
        if (u.authid != null) r.attributes.add(u.authid.toUpperCase());
        u.decls.routines.add(r);
    }

    private void standaloneFunction(Create_function_bodyContext c) {
        Function_nameContext n = c.function_name();
        boolean q = n.PERIOD() != null;
        PlsqlUnit u = newUnit("FUNCTION", c, q ? text(n.identifier()) : null, q ? text(n.id_expression()) : text(n.identifier()));
        Routine r = routine(c, "FUNCTION", u.name, c.parameter(), c.type_spec(), "STANDALONE", null, "STANDALONE",
                c.body(), c.seq_of_declare_specs(), c.call_spec());
        u.decls.routines.add(r);
    }

    private void trigger(Create_triggerContext c) {
        Trigger_nameContext n = c.trigger_name();
        boolean q = n.PERIOD() != null;
        PlsqlUnit u = newUnit("TRIGGER", c, q ? text(n.identifier()) : null, q ? text(n.id_expression()) : text(n.identifier()));
        Trigger_bodyContext body = c.trigger_body();
        if (body != null) {
            int from = n.getStop().getStopIndex() + 1, to = body.getStart().getStartIndex() - 1;
            if (to >= from) u.triggerHeader = norm(c.getStart().getInputStream().getText(Interval.of(from, to)));
        }
        if (body != null) unitBodies.put(u, List.of(body));
        // SQL and calls inside the trigger body are extracted; its control-flow outline arrives with the logic milestone
        skipped.add(new SkippedItem(file, pu.variant(), "trigger_body", u.name, body == null ? u.line : body.getStart().getLine(),
                body == null ? u.endLine : endLine(body), "trigger body: SQL and calls extracted, logic outline pending"));
    }

    private void anonymousBlock(Anonymous_blockContext c) {
        PlsqlUnit u = newUnit("ANONYMOUS_BLOCK", c, null, "<anonymous@" + c.getStart().getLine() + ">");
        if (c.seq_of_declare_specs() != null)
            collect(c.seq_of_declare_specs().declare_spec(), u.decls, "ANONYMOUS", u.name, "LOCAL");
        List<ParserRuleContext> blocks = new ArrayList<>();
        if (c.seq_of_statements() != null) blocks.add(c.seq_of_statements());
        blocks.addAll(c.exception_handler());
        unitBodies.put(u, blocks);
    }

    // ------------------------------------------------------------------ declarations

    /** Collect declarations from a list of holders (package_obj_spec / package_obj_body / declare_spec). */
    private void collect(List<? extends ParserRuleContext> holders, Declarations d, String scope, String owner, String vis) {
        Map<String, Integer> exceptionCodes = new HashMap<>();
        for (ParserRuleContext h : holders) {
            if (h.getChildCount() == 0) continue;
            ParseTree c = h.getChild(0);
            if (c instanceof Variable_declarationContext v) variable(v, d);
            else if (c instanceof Type_declarationContext t) typeDecl(t, d);
            else if (c instanceof Subtype_declarationContext s) subtype(s, d);
            else if (c instanceof Cursor_declarationContext cu) cursor(cu, d);
            else if (c instanceof Exception_declarationContext e)
                d.exceptions.add(new ExceptionDecl(text(e.identifier()), null, e.getStart().getLine()));
            else if (c instanceof Pragma_declarationContext p) pragma(p, d, exceptionCodes);
            else if (c instanceof Procedure_specContext ps) d.routines.add(procedureSpec(ps, scope, owner, vis));
            else if (c instanceof Function_specContext fs) d.routines.add(functionSpec(fs, scope, owner, vis));
            else if (c instanceof Procedure_bodyContext pb) d.routines.add(procedureBody(pb, scope, owner, vis));
            else if (c instanceof Function_bodyContext fb) d.routines.add(functionBody(fb, scope, owner, vis));
            else if (c instanceof Selection_directiveContext) skip((ParserRuleContext) c, "selection_directive",
                    "unresolved conditional compilation inside declarations");
            else skip((ParserRuleContext) c, ruleName((ParserRuleContext) c), "unmodelled declaration");
        }
        if (!exceptionCodes.isEmpty()) {
            for (int i = 0; i < d.exceptions.size(); i++) {
                ExceptionDecl e = d.exceptions.get(i);
                Integer code = exceptionCodes.get(e.name().toLowerCase());
                if (code != null) d.exceptions.set(i, new ExceptionDecl(e.name(), code, e.line()));
            }
        }
    }

    private void variable(Variable_declarationContext v, Declarations d) {
        if (v.default_value_part() != null) initCtx.computeIfAbsent(d, k -> new ArrayList<>()).add(v.default_value_part());
        d.variables.add(new Variable(text(v.identifier()), v.CONSTANT() != null, norm(text(v.type_spec())),
                v.NOT() != null && v.NULL_() != null, v.default_value_part() == null ? null : defaultText(v.default_value_part()),
                v.getStart().getLine()));
    }

    private void typeDecl(Type_declarationContext t, Declarations d) {
        String kind;
        String def;
        List<Field> fields = new ArrayList<>();
        if (t.record_type_def() != null) {
            kind = "RECORD";
            def = norm(text(t.record_type_def()));
            for (Field_specContext f : t.record_type_def().field_spec())
                fields.add(new Field(text(f.column_name()), f.type_spec() == null ? "" : norm(text(f.type_spec())),
                        f.default_value_part() == null ? null : defaultText(f.default_value_part())));
        } else if (t.table_type_def() != null) {
            kind = "TABLE";
            def = norm(text(t.table_type_def()));
        } else if (t.varray_type_def() != null) {
            kind = "VARRAY";
            def = norm(text(t.varray_type_def()));
        } else {
            kind = "REF_CURSOR";
            def = norm(text(t.ref_cursor_type_def()));
        }
        d.types.add(new TypeDecl(text(t.identifier()), kind, def, fields, t.getStart().getLine(), endLine(t)));
    }

    private void subtype(Subtype_declarationContext s, Declarations d) {
        d.types.add(new TypeDecl(text(s.identifier()), "SUBTYPE", norm(text(s.type_spec())), List.of(),
                s.getStart().getLine(), endLine(s)));
    }

    private void cursor(Cursor_declarationContext c, Declarations d) {
        List<Param> ps = new ArrayList<>();
        for (Parameter_specContext p : c.parameter_spec())
            ps.add(new Param(text(p.parameter_name()), "IN", p.type_spec() == null ? "" : norm(text(p.type_spec())),
                    p.default_value_part() == null ? null : defaultText(p.default_value_part()), p.getStart().getLine()));
        d.cursors.add(new CursorDecl(text(c.identifier()), ps, c.type_spec() == null ? null : norm(text(c.type_spec())),
                c.select_statement() == null ? null : norm(text(c.select_statement())), c.getStart().getLine(), endLine(c)));
        cursorCtx.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
    }

    private void pragma(Pragma_declarationContext p, Declarations d, Map<String, Integer> exceptionCodes) {
        String name = p.getChild(1).getText().toUpperCase();
        d.pragmas.add(new PragmaDecl(name, norm(text(p)), p.getStart().getLine()));
        if (p.EXCEPTION_INIT() != null && p.exception_name() != null && p.numeric_negative() != null) {
            try {
                exceptionCodes.put(text(p.exception_name()).toLowerCase(), Integer.parseInt(norm(text(p.numeric_negative())).replace(" ", "")));
            } catch (NumberFormatException ignored) {
                // leave the code unknown; the pragma text is still recorded
            }
        }
    }

    // ------------------------------------------------------------------ routines

    private Routine procedureSpec(Procedure_specContext c, String scope, String owner, String vis) {
        Routine r = routine(c, "PROCEDURE", text(c.identifier()), c.parameter(), null, scope, owner, vis, null, null, c.call_spec());
        r.forwardDeclaration = scope.equals("PACKAGE_BODY") || scope.equals("LOCAL");
        return r;
    }

    private Routine functionSpec(Function_specContext c, String scope, String owner, String vis) {
        Routine r = routine(c, "FUNCTION", text(c.identifier()), c.parameter(), c.type_spec(), scope, owner, vis, null, null, c.call_spec());
        r.forwardDeclaration = scope.equals("PACKAGE_BODY") || scope.equals("LOCAL");
        return r;
    }

    private Routine procedureBody(Procedure_bodyContext c, String scope, String owner, String vis) {
        return routine(c, "PROCEDURE", text(c.identifier()), c.parameter(), null, scope, owner, vis, c.body(), c.seq_of_declare_specs(), c.call_spec());
    }

    private Routine functionBody(Function_bodyContext c, String scope, String owner, String vis) {
        return routine(c, "FUNCTION", text(c.identifier()), c.parameter(), c.type_spec(), scope, owner, vis, c.body(), c.seq_of_declare_specs(), c.call_spec());
    }

    private Routine routine(ParserRuleContext ctx, String kind, String name, List<ParameterContext> ps, Type_specContext ret,
                            String scope, String owner, String vis, BodyContext body, Seq_of_declare_specsContext decls,
                            Call_specContext callSpec) {
        Routine r = new Routine();
        r.kind = kind;
        r.name = name;
        r.owner = owner;
        r.scope = scope;
        r.visibility = vis;
        r.file = file;
        r.variant = pu.variant();
        r.line = ctx.getStart().getLine();
        r.endLine = endLine(ctx);
        r.source = text(ctx);
        r.hasBody = body != null;
        if (callSpec != null) r.callSpec = norm(text(callSpec));
        for (ParameterContext p : ps) {
            r.params.add(param(p));
            if (p.default_value_part() != null) paramDefaultCtx.computeIfAbsent(r, k -> new ArrayList<>()).add(p.default_value_part());
        }
        if (ret != null) r.returnType = norm(text(ret));
        for (ParseTree ch : ctx.children) {
            if (ch instanceof TerminalNode t) {
                switch (t.getSymbol().getType()) {
                    case PlSqlLexer.DETERMINISTIC -> r.attributes.add("DETERMINISTIC");
                    case PlSqlLexer.PIPELINED -> r.attributes.add("PIPELINED");
                    case PlSqlLexer.RESULT_CACHE -> r.attributes.add("RESULT_CACHE");
                    case PlSqlLexer.PARALLEL_ENABLE -> r.attributes.add("PARALLEL_ENABLE");
                    case PlSqlLexer.AGGREGATE -> r.attributes.add("AGGREGATE");
                    default -> { }
                }
            } else if (ch instanceof ParserRuleContext pc) {
                String rn = ruleName(pc);
                if (rn.equals("invoker_rights_clause") || rn.equals("accessible_by_clause") || rn.equals("result_cache_clause")
                        || rn.equals("parallel_enable_clause") || rn.equals("streaming_clause"))
                    r.attributes.add(norm(text(pc)).toUpperCase());
            }
        }
        if (decls != null) collect(decls.declare_spec(), r.locals, "LOCAL", name, "LOCAL");
        if (r.locals.hasPragma("AUTONOMOUS_TRANSACTION")) r.attributes.add("AUTONOMOUS_TRANSACTION");
        if (body != null && body.BEGIN() != null) {
            r.bodyLine = body.BEGIN().getSymbol().getLine();
            r.bodyHeaderComments.addAll(commentsRight(body.BEGIN().getSymbol()));
        }
        r.docComments.addAll(commentsLeft(ctx.getStart()));
        r.signature = signature(r);
        routineCtx.put(r, new RoutineCtx(ctx, body, decls));
        for (Routine nested : r.locals.routines) parent.put(nested, r);
        return r;
    }

    private Param param(ParameterContext p) {
        boolean in = !p.IN().isEmpty(), out = !p.OUT().isEmpty() || !p.INOUT().isEmpty(), nocopy = !p.NOCOPY().isEmpty();
        String mode = in && out || !p.INOUT().isEmpty() ? "IN OUT" : out ? "OUT" : "IN";
        if (nocopy) mode += " NOCOPY";
        return new Param(text(p.parameter_name()), mode, p.type_spec() == null ? "" : norm(text(p.type_spec())),
                p.default_value_part() == null ? null : defaultText(p.default_value_part()), p.getStart().getLine());
    }

    private static String signature(Routine r) {
        StringBuilder b = new StringBuilder(r.kind.toLowerCase()).append(' ').append(r.name);
        if (!r.params.isEmpty()) {
            b.append('(');
            for (int i = 0; i < r.params.size(); i++) {
                Param p = r.params.get(i);
                if (i > 0) b.append(", ");
                b.append(p.name()).append(' ').append(p.mode().toLowerCase()).append(' ').append(p.type());
                if (p.defaultValue() != null) b.append(" := ").append(p.defaultValue());
            }
            b.append(')');
        }
        if (r.returnType != null) b.append(" return ").append(r.returnType);
        return b.toString();
    }

    // ------------------------------------------------------------------ comments

    private static boolean isComment(Token t) {
        int ty = t.getType();
        return ty == PlSqlLexer.SINGLE_LINE_COMMENT || ty == PlSqlLexer.MULTI_LINE_COMMENT || ty == PlSqlLexer.REMARK_COMMENT;
    }

    /** Comments directly above a token (at most one blank line between), excluding trailing comments of the previous statement. */
    private List<String> commentsLeft(Token start) {
        List<Token> hidden = tokens.getHiddenTokensToLeft(start.getTokenIndex());
        if (hidden == null) return List.of();
        Deque<String> out = new ArrayDeque<>();
        for (int i = hidden.size() - 1; i >= 0; i--) {
            Token t = hidden.get(i);
            if (t.getType() == PlSqlLexer.SPACES) {
                if (count(t.getText(), '\n') > 2) break;
            } else if (isComment(t)) {
                if (startsAfterCode(t)) break;
                out.addFirst(clean(t.getText()));
            } else break;
        }
        return new ArrayList<>(out);
    }

    /** Comments immediately after a token (e.g. the header comment following BEGIN). */
    private List<String> commentsRight(Token t0) {
        List<Token> hidden = tokens.getHiddenTokensToRight(t0.getTokenIndex());
        if (hidden == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Token t : hidden) {
            if (t.getType() == PlSqlLexer.SPACES) {
                if (count(t.getText(), '\n') > 2 && !out.isEmpty()) break;
            } else if (isComment(t)) out.add(clean(t.getText()));
        }
        return out;
    }

    /** True if the comment is on the same line as preceding code (a trailing comment, not a header). */
    private boolean startsAfterCode(Token comment) {
        for (int i = comment.getTokenIndex() - 1; i >= 0; i--) {
            Token p = tokens.get(i);
            if (p.getChannel() == Token.DEFAULT_CHANNEL) return p.getLine() == comment.getLine();
            if (p.getType() == PlSqlLexer.SPACES && p.getText().indexOf('\n') >= 0) return false;
        }
        return false;
    }

    private static String clean(String c) {
        String s = c.strip();
        if (s.startsWith("/*")) s = s.substring(2, Math.max(2, s.length() - 2));
        else if (s.startsWith("--")) s = s.substring(2);
        return s.strip();
    }

    // ------------------------------------------------------------------ helpers

    private String text(ParserRuleContext c) {
        if (c == null || c.getStart() == null || c.getStop() == null) return "";
        return c.getStart().getInputStream().getText(Interval.of(c.getStart().getStartIndex(), c.getStop().getStopIndex()));
    }

    private String defaultText(Default_value_partContext d) {
        return norm(text(d)).replaceFirst("(?i)^(:=|default)\\s*", "");
    }

    private String ruleName(ParserRuleContext c) {
        return PlSqlParser.ruleNames[c.getRuleIndex()];
    }

    private static int endLine(ParserRuleContext c) {
        Token s = c.getStop();
        return s == null ? c.getStart().getLine() : s.getLine() + count(s.getText(), '\n');
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }
}
