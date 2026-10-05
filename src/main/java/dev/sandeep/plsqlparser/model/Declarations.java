package dev.sandeep.plsqlparser.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Everything declared in one scope: a package spec/body, a standalone unit, or a routine's declare section. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Declarations {
    public record Variable(String name, boolean constant, String type, boolean notNull, String defaultValue, int line) {}
    public record Field(String name, String type, String defaultValue) {}
    /** kind: RECORD | TABLE | VARRAY | REF_CURSOR | SUBTYPE */
    public record TypeDecl(String name, String kind, String definition, List<Field> fields, int line, int endLine) {}
    public record CursorDecl(String name, List<Param> params, String returnType, String query, int line, int endLine) {}
    public record ExceptionDecl(String name, Integer errorCode, int line) {}
    public record PragmaDecl(String name, String text, int line) {}

    public final List<Variable> variables = new ArrayList<>();
    public final List<TypeDecl> types = new ArrayList<>();
    public final List<CursorDecl> cursors = new ArrayList<>();
    public final List<ExceptionDecl> exceptions = new ArrayList<>();
    public final List<PragmaDecl> pragmas = new ArrayList<>();
    public final List<Routine> routines = new ArrayList<>();

    public boolean hasPragma(String name) {
        return pragmas.stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }
}
