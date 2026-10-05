package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What can leave a routine as an exception, and how the routine handles exceptions itself. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ExceptionContract {
    /** how: RAISE (named), RE_RAISE (bare RAISE), RAISE_APPLICATION_ERROR. */
    public record Raise(String exception, String how, Integer errorCode, String codeExpression, int line) {}

    /** One WHEN ... THEN handler. {@code swallows}: no RAISE / RAISE_APPLICATION_ERROR inside, so the error stops here. */
    public record Handler(List<String> exceptions, int line, int endLine, boolean others, boolean reraises, boolean swallows,
                          boolean onlyNull, boolean callsRoutines, boolean returnsValue, boolean endsTransaction) {}

    public final List<Raise> raises = new ArrayList<>();
    public final List<Handler> handlers = new ArrayList<>();
    /** Exceptions the engine can raise implicitly, e.g. NO_DATA_FOUND / TOO_MANY_ROWS from SELECT INTO. */
    public final Set<String> implicit = new LinkedHashSet<>();

    public boolean handlesOthers() {
        return handlers.stream().anyMatch(Handler::others);
    }

    public boolean handles(String name) {
        return handlers.stream().anyMatch(h -> h.exceptions().stream().anyMatch(e -> e.equalsIgnoreCase(name)));
    }
}
