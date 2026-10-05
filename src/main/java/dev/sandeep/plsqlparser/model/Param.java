package dev.sandeep.plsqlparser.model;

/** Formal parameter of a routine or cursor. {@code mode} is IN, OUT, IN OUT (optionally + NOCOPY). */
public record Param(String name, String mode, String type, String defaultValue, int line) {
    /** Signature fragment used for spec/body matching and overload detection: the type, normalised. */
    public String typeKey() {
        return type == null ? "" : type.toLowerCase().replaceAll("\s+", "");
    }
}
