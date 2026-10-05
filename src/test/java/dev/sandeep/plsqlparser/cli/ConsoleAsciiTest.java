package dev.sandeep.plsqlparser.cli;

import dev.sandeep.plsqlparser.emit.Verifier;
import dev.sandeep.plsqlparser.run.Ascii;
import dev.sandeep.plsqlparser.run.Pipeline;
import dev.sandeep.plsqlparser.run.Progress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Windows console shows an em dash as "?" or a box, so everything printed to the console must be plain ASCII. */
class ConsoleAsciiTest {
    private static void assertAscii(String label, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++)
            assertTrue(bytes[i] >= 0, label + ": non-ASCII byte at " + i + " in: " + new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void symbolsBecomePlainAscii() {
        assertEquals("a - b -> c ... d <-> e x f >= g", Ascii.clean("a — b → c … d ↔ e × f ≥ g"));
        assertEquals("\"quoted\" it's", Ascii.clean("“quoted” it’s"));
    }

    @Test
    void plainTextIsReturnedUnchanged() {
        String s = "PASS Every file parses - 0 syntax errors";
        assertSame(s, Ascii.clean(s));
        assertNull(Ascii.clean(null));
    }

    @Test
    void lettersInFileNamesAreKept() {
        assertEquals("café.pkb - ok", Ascii.clean("café.pkb — ok"));
    }

    @Test
    void consoleProgressIsAscii() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Progress p = Progress.console(new PrintStream(buf, true, StandardCharsets.UTF_8));
        p.stage("parse", "Parsing — conditional compilation first…");
        p.file("a.pkb", 1, 2, 0);
        p.log("PASS Parse confidence — 100/100 → grade A");
        assertAscii("progress", buf.toByteArray());
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("Parsing - conditional compilation first..."));
    }

    @Test
    void verdictPrinterIsAscii() {
        var checks = List.of(new Verifier.Check("links", "Links", "WARN", "x — y", List.of("a.pkb → missing.md", "…")));
        var report = new Verifier.Report("VERIFIED_WITH_WARNINGS", "Structure fine — one warning", 97, "A", 2, 3, checks);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Main.printVerdict(new PrintStream(buf, true, StandardCharsets.UTF_8), report);
        assertAscii("verdict", buf.toByteArray());
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("a.pkb -> missing.md"));
    }

    @Test
    void aWholeBuildPrintsOnlyAscii(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in");
        Files.createDirectories(in);
        Files.writeString(in.resolve("b_proc.prc"), "create or replace procedure b_proc is\nbegin\n  null;\nend;\n/\n");
        Files.writeString(in.resolve("a_proc.prc"), "create or replace procedure a_proc is\nbegin\n  b_proc;\n  missing_pkg.go;\nend;\n/\n");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var o = Pipeline.run(in, List.of(in), Map.of(), in.resolve(".agentdocs"), true, Progress.console(ps));
        Main.printVerdict(ps, o.docs().verdict());
        assertAscii("whole build", buf.toByteArray());
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("VERDICT:"));
    }
}
