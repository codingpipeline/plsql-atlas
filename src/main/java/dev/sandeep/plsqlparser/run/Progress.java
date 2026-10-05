package dev.sandeep.plsqlparser.run;

/** Receives what a build is doing, so a console or the live UI can show it. All methods may be called from worker threads. */
public interface Progress {
    String DISCOVER = "discover", PARSE = "parse", ANALYSE = "analyse", WRITE = "write", VERIFY = "verify";

    /** A stage starts ({@code detail} is a short human sentence). */
    void stage(String id, String detail);

    /** A file finished parsing: {@code done} of {@code total}, with its syntax error count. */
    void file(String file, int done, int total, int syntaxErrors);

    void log(String line);

    /** The run ended; {@code verdict} is the Verifier verdict ("VERIFIED", "VERIFIED_WITH_WARNINGS", "NOT_VERIFIED") or null on failure. */
    void finished(String verdict, String message);

    Progress NONE = new Progress() {
        @Override public void stage(String id, String detail) { }
        @Override public void file(String file, int done, int total, int syntaxErrors) { }
        @Override public void log(String line) { }
        @Override public void finished(String verdict, String message) { }
    };

    /** Prints a compact live feed to a stream. */
    static Progress console(java.io.PrintStream out) {
        return new Progress() {
            @Override public void stage(String id, String detail) { out.println("[" + id + "] " + detail); }
            @Override public void file(String file, int done, int total, int syntaxErrors) {
                out.printf("  parsed %d/%d %s%s%n", done, total, file, syntaxErrors > 0 ? "  (" + syntaxErrors + " syntax error(s))" : "");
            }
            @Override public void log(String line) { out.println("  " + line); }
            @Override public void finished(String verdict, String message) { }
        };
    }
}
