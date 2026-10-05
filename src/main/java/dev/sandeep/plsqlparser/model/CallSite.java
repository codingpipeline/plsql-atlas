package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** A call to a procedure or function found in a routine body, with its resolution. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class CallSite {
    public static final String INTERNAL = "INTERNAL";               // routine found in the scanned code
    public static final String LOCAL = "LOCAL";                     // nested subprogram
    public static final String BUILTIN = "BUILTIN";                 // Oracle-supplied package / standard routine
    public static final String UNRESOLVED_IN_PACKAGE = "UNRESOLVED_IN_KNOWN_PACKAGE";
    public static final String EXTERNAL = "EXTERNAL_UNRESOLVED";    // qualified name whose package is not in the scanned code
    public static final String UNRESOLVED = "UNRESOLVED";           // unqualified name nothing matched

    public String callee;        // as written, without arguments, e.g. logger.log_error
    public int line;
    /** Character offset of the call in its source (internal: links outline steps to calls). */
    @com.fasterxml.jackson.annotation.JsonIgnore public int offset;
    public int argCount;
    public final List<String> namedArgs = new ArrayList<>();
    public boolean statement;    // call used as a statement (procedure call) vs inside an expression
    public boolean parens;       // written with an argument list (false: parameterless call or constant reference)
    public boolean inLoop;
    public String text;          // call text snippet (normalised, truncated)

    // filled by CallResolver
    public String resolution;
    public String targetId;                           // resolved routine id when unique
    public final List<String> candidates = new ArrayList<>(); // overloads still possible
    public String builtinPackage;                     // e.g. DBMS_OUTPUT
}
