package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A procedure or function as declared in a spec, defined in a body, standalone, or nested inside another routine. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Routine {
    public String id;                    // stable id, e.g. LOGGER.LOG_ERROR#2
    public String kind;                  // PROCEDURE | FUNCTION
    public String name;
    public String owner;                 // package / enclosing routine / null for standalone
    public String scope;                 // PACKAGE_SPEC | PACKAGE_BODY | STANDALONE | LOCAL | ANONYMOUS
    public String visibility;            // PUBLIC | PRIVATE | STANDALONE | LOCAL
    public boolean forwardDeclaration;   // spec-only declaration inside a body
    public boolean hasBody;              // implementation present (false for spec-only / call spec)
    public String callSpec;              // set for EXTERNAL / LANGUAGE JAVA|C routines
    public final List<Param> params = new ArrayList<>();
    public String returnType;            // functions only
    public final Set<String> attributes = new LinkedHashSet<>(); // DETERMINISTIC, PIPELINED, RESULT_CACHE, AUTHID ..., AUTONOMOUS_TRANSACTION
    public int line, endLine;            // whole declaration/definition
    public int bodyLine;                 // line of BEGIN (0 if none)
    public String signature;             // one-line normalised signature
    public String source;                // verbatim source text of the declaration/definition
    public final List<String> docComments = new ArrayList<>();   // comments directly above the routine
    public final List<String> bodyHeaderComments = new ArrayList<>(); // comments right after BEGIN
    public Declarations locals = new Declarations();             // local declarations incl. nested routines
    public final List<SqlStatement> sql = new ArrayList<>();
    public final List<CallSite> calls = new ArrayList<>();
    public List<LogicStep> outline = new ArrayList<>();               // lossless control-flow outline of the body
    public ExceptionContract exceptionContract = new ExceptionContract();
    public SideEffects sideEffects = new SideEffects();
    public Metrics metrics = new Metrics();
    public final List<Risk> risks = new ArrayList<>();
    public final Set<String> tablesRead = new LinkedHashSet<>();     // keys, e.g. SCOTT.EMP
    public final Set<String> tablesWritten = new LinkedHashSet<>();
    public String file;
    public String variant;               // primary | alternate
    public Integer specLine;             // linked: line of the public declaration in the spec
    public Integer bodyDefLine;          // linked: line of the implementation in the body
    public String linkNote;              // e.g. "matched by name+arity (types differ)"

    /** name(type,type) key for spec/body matching. */
    public String signatureKey() {
        StringBuilder b = new StringBuilder(name.toLowerCase()).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) b.append(',');
            b.append(params.get(i).typeKey());
        }
        b.append(')');
        if (returnType != null) b.append(':').append(returnType.toLowerCase().replaceAll("\s+", ""));
        return b.toString();
    }

    public String arityKey() {
        return name.toLowerCase() + "/" + params.size();
    }
}
