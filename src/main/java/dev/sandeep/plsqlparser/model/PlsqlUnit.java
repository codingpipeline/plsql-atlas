package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** One top-level compilation unit found in a file (package spec/body, standalone routine, trigger, anonymous block). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class PlsqlUnit {
    public String kind;      // PACKAGE_SPEC | PACKAGE_BODY | PROCEDURE | FUNCTION | TRIGGER | ANONYMOUS_BLOCK
    public String schema;
    public String name;
    public String file;
    public String variant;
    public int line, endLine;
    public String authid;
    public String triggerHeader;     // triggers: timing/event/table clause, normalised
    public final List<String> docComments = new ArrayList<>();
    public Declarations decls = new Declarations();
    /** SQL and calls outside any routine: package init block, trigger body, anonymous block, package-level cursors. */
    public final List<SqlStatement> sql = new ArrayList<>();
    public final List<CallSite> calls = new ArrayList<>();
    /** Outline / contract / effects of statements outside routines (init block, trigger body, anonymous block). */
    public List<LogicStep> outline = new ArrayList<>();
    public ExceptionContract exceptionContract = new ExceptionContract();
    public SideEffects sideEffects = new SideEffects();
    public final List<Risk> risks = new ArrayList<>();
    public boolean hasInitBlock;     // package body BEGIN ... END initialisation section
    public int initBlockLine;

    public String qualifiedName() {
        return schema == null ? name : schema + "." + name;
    }

    /** Case-insensitive key shared by a package's spec and body. */
    public String key() {
        return (name == null ? "" : name.toLowerCase().replace("\"", ""));
    }
}
