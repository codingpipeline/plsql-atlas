package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of a routine's control-flow outline. Statements nest exactly as in the source, so the tree is lossless
 * with respect to control flow. {@code refs} link a step to the tables it touches and the routines it calls.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class LogicStep {
    /** Kinds that nest other steps. */
    public static final java.util.Set<String> COMPOUND = java.util.Set.of("IF", "THEN", "ELSIF", "ELSE", "CASE", "WHEN", "LOOP",
            "WHILE_LOOP", "FOR_LOOP", "CURSOR_FOR_LOOP", "FORALL", "BLOCK", "EXCEPTION_SECTION", "HANDLER");

    public String kind;      // ASSIGN, IF/THEN/ELSIF/ELSE, CASE/WHEN, LOOP..., SQL, DYNAMIC_SQL, CALL, RAISE, RETURN, COMMIT...
    public String text;      // condition / expression / statement text (normalised, clipped)
    public String label;     // statement label (<<label>>) or loop label
    public int line, endLine;
    public final List<String> refs = new ArrayList<>();
    public final List<LogicStep> children = new ArrayList<>();

    public LogicStep() {}

    public LogicStep(String kind, String text, int line, int endLine) {
        this.kind = kind;
        this.text = text;
        this.line = line;
        this.endLine = endLine;
    }
}
