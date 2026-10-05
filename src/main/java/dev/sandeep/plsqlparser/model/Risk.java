package dev.sandeep.plsqlparser.model;

/** A migration/maintenance hazard found by a rule. severity: HIGH | MEDIUM | LOW | INFO. */
public record Risk(String code, String severity, String message, String routineId, String file, int line) {}
