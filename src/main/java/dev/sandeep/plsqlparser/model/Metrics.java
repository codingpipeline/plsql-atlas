package dev.sandeep.plsqlparser.model;

/** Size and complexity figures for one routine. */
public class Metrics {
    public int linesOfCode;
    public int statements;       // statement nodes in the body, nested included
    public int decisionPoints;   // IF/ELSIF/CASE WHEN/loops/handlers
    public int cyclomatic;       // 1 + decisionPoints
    public int maxNesting;       // deepest nesting of compound steps
    public int loops;
    public int sqlStatements;
    public int calls;
}
