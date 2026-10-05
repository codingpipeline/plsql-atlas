package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** M2 result: the structural model of every parsed source, plus everything we could not or did not model. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class StructureModel {
    /** A top-level statement that is not PL/SQL structure (DDL, DML, SQL*Plus...) — listed, never silently dropped. */
    public record SkippedItem(String file, String variant, String kind, String name, int line, int endLine, String reason) {}
    public record Issue(String severity, String code, String message, String file, int line) {}
    public record FileInfo(String file, String variant, int lines, int syntaxErrors, int undecidedConditions, List<String> undecided) {}

    public final List<FileInfo> files = new ArrayList<>();
    /** Units from the primary variant of every file. */
    public final List<PlsqlUnit> units = new ArrayList<>();
    /** Routines that exist only in an alternate $IF variant (so conditional-compilation logic is not lost). */
    public final List<Routine> alternateOnlyRoutines = new ArrayList<>();
    public final List<SkippedItem> skipped = new ArrayList<>();
    /** Tables declared by DDL in the scanned files (primary variants); also listed in {@link #skipped} as not-PL/SQL. */
    public final List<TableDef> tableDefs = new ArrayList<>();
    public final List<Issue> issues = new ArrayList<>();
    /** Every risk found, across routines and unit-level blocks (also attached to the routines themselves). */
    public final List<Risk> risks = new ArrayList<>();
}
