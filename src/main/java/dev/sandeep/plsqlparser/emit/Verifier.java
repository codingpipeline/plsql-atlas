package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sandeep.plsqlparser.model.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Cross-checks a finished build against the inputs and against itself, and reports a verdict. It re-reads what is on disk
 * (cards, links, source copies) rather than trusting the in-memory model, so a writer bug cannot hide behind itself.
 */
public final class Verifier {
    public static final String PASS = "PASS", WARN = "WARN", FAIL = "FAIL", SKIP = "SKIP";
    public static final String VERIFIED = "VERIFIED", WITH_WARNINGS = "VERIFIED_WITH_WARNINGS", NOT_VERIFIED = "NOT_VERIFIED";
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#\\s]+)(?:#[^)]*)?\\)");

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Check(String id, String title, String status, String evidence, List<String> details) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Report(String verdict, String summary, int confidence, String grade, int files, int routines, List<Check> checks) {}

    private final DocContext ctx;
    private final ReportWriter rep;
    private final Path root, out;
    private final boolean copySource;
    private final List<Check> checks = new ArrayList<>();

    Verifier(DocContext ctx, ReportWriter rep, Path root, Path out, boolean copySource) {
        this.ctx = ctx;
        this.rep = rep;
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
        this.copySource = copySource;
    }

    Report run() throws IOException {
        StructureModel m = ctx.model;
        List<String> primaryFiles = m.files.stream().filter(f -> f.variant().equals("primary")).map(StructureModel.FileInfo::file).distinct().sorted().toList();
        add("files", "PL/SQL files found", primaryFiles.isEmpty() ? FAIL : PASS, primaryFiles.size() + " file(s) discovered and parsed", primaryFiles.isEmpty() ? List.of("no PL/SQL files in the folder") : List.of());

        List<String> bad = m.files.stream().filter(f -> f.variant().equals("primary") && f.syntaxErrors() > 0).map(f -> f.file() + ": " + f.syntaxErrors() + " syntax error(s)").toList();
        add("syntax", "Every file parses without syntax errors", bad.isEmpty() ? PASS : FAIL,
                bad.isEmpty() ? "0 syntax errors" : bad.size() + " file(s) with syntax errors: the parser recovered, so nearby logic may be incomplete", bad);

        int unaccounted = rep.coverage.stream().mapToInt(c -> c.unaccountedCount()).sum();
        add("coverage", "Every code line is accounted for", unaccounted == 0 ? PASS : FAIL,
                unaccounted == 0 ? "all code lines belong to a recognised unit, a listed skipped statement or a terminator" : unaccounted + " code line(s) are in no unit or listed statement",
                rep.coverage.stream().filter(c -> c.unaccountedCount() > 0).map(c -> c.file() + " lines " + c.unaccounted().stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.joining(", "))).toList());

        List<String> proof = new ArrayList<>();
        for (var i : m.issues) if (i.code().equals("outline-mismatch") || i.code().equals("analysis-failed")) proof.add(i.file() + ":" + i.line() + " " + i.message());
        int unknownSteps = 0;
        for (Routine r : ctx.routineById.values()) unknownSteps += countUnknown(r.outline, proof, r.id);
        add("statements", "Every statement became exactly one logic step", proof.isEmpty() ? PASS : FAIL,
                proof.isEmpty() ? "statement count equals outline step count for every routine; no unmodelled statement kinds" : proof.size() + " mismatch(es) or unmodelled statement(s)", proof);

        int rt = rep.roundTripBadIds.size();
        add("roundtrip", "Card source spans equal the original files", rt == 0 ? PASS : FAIL,
                rt == 0 ? rep.roundTripExact + " identical, " + rep.roundTripViaIf + " identical apart from $IF-excluded text" : rt + " routine(s) whose span differs from the file", rep.roundTripBadIds);

        List<String> missingCards = new ArrayList<>();
        for (String p : ctx.cardPath.values()) {
            if (!Files.isRegularFile(out.resolve(p))) missingCards.add(p);
            if (!Files.isRegularFile(out.resolve(p.replaceFirst("\\.md$", ".json")))) missingCards.add(p.replaceFirst("\\.md$", ".json"));
        }
        for (String p : ctx.unitCardPath.values()) if (!Files.isRegularFile(out.resolve(p))) missingCards.add(p);
        for (String p : ctx.pkgPath.values()) if (!Files.isRegularFile(out.resolve(p))) missingCards.add(p);
        for (String p : ctx.tablePath.values()) if (!Files.isRegularFile(out.resolve(p))) missingCards.add(p);
        int expected = ctx.cardPath.size() + ctx.unitCardPath.size() + ctx.pkgPath.size() + ctx.tablePath.size();
        add("cards", "Every routine, package and table has its page", missingCards.isEmpty() ? PASS : FAIL,
                missingCards.isEmpty() ? expected + " pages exist on disk (routine cards also as JSON)" : missingCards.size() + " page(s) missing on disk", missingCards);

        List<String> broken = brokenLinks();
        add("links", "Every relative link in the docs resolves", broken.isEmpty() ? PASS : FAIL,
                broken.isEmpty() ? "all relative links point at existing files" : broken.size() + " broken link(s)", broken.stream().limit(20).toList());

        if (copySource) {
            List<String> diff = new ArrayList<>();
            for (String f : primaryFiles) {
                Path a = root.resolve(f), b = out.resolve("source").resolve(f);
                try {
                    if (!Files.isRegularFile(b) || !Arrays.equals(sha(a), sha(b))) diff.add(f);
                } catch (Exception e) {
                    diff.add(f + " (" + e.getMessage() + ")");
                }
            }
            add("source", "source/ copies are byte-identical to the originals", diff.isEmpty() ? PASS : FAIL,
                    diff.isEmpty() ? primaryFiles.size() + " file(s) compared by SHA-256" : diff.size() + " copy/copies differ", diff);
        } else add("source", "source/ copies are byte-identical to the originals", SKIP, "source copy disabled (--no-source)", List.of());

        long calls = rep.fileDeps == null ? 0 : rep.fileDeps.edges().stream().filter(e -> e.type().equals("CALLS")).count();
        long pairs = rep.fileDeps == null ? 0 : rep.fileDeps.edges().stream().filter(e -> e.type().equals("SPEC_BODY")).count();
        long tables = rep.fileDeps == null ? 0 : rep.fileDeps.edges().stream().filter(e -> e.type().equals("SHARED_TABLE")).count();
        long ddl = rep.fileDeps == null ? 0 : rep.fileDeps.edges().stream().filter(e -> e.type().equals("NEEDS_DDL")).count();
        String cross = "file relations mapped: " + calls + " call, " + pairs + " spec/body, " + ddl + " table-DDL, " + tables + " shared-table";
        List<String> open = rep.confidence.missing().stream().filter(x -> x.kind().equals("EXTERNAL_PACKAGE") || x.kind().equals("UNRESOLVED_CALL"))
                .map(x -> x.kind().equals("EXTERNAL_PACKAGE") ? "package " + x.name() + " is called but not in the folder" : "call " + x.name() + " matches nothing").toList();
        add("crossfile", "References between files are resolved", open.isEmpty() ? PASS : WARN,
                cross + (open.isEmpty() ? "" : "; " + open.size() + " reference(s) point outside the folder"), open);

        int conf = rep.confidence.overall();
        add("confidence", "Parse confidence", conf >= 90 ? PASS : conf >= 60 ? WARN : FAIL, conf + "/100 (grade " + rep.confidence.grade() + ")",
                rep.confidence.missing().stream().filter(x -> x.severity().equals("HIGH")).map(x -> x.kind() + " " + x.name() + " — " + x.detail()).limit(10).toList());

        String verdict = checks.stream().anyMatch(c -> c.status().equals(FAIL)) ? NOT_VERIFIED : checks.stream().anyMatch(c -> c.status().equals(WARN)) ? WITH_WARNINGS : VERIFIED;
        long f = checks.stream().filter(c -> c.status().equals(FAIL)).count(), w = checks.stream().filter(c -> c.status().equals(WARN)).count();
        String summary = switch (verdict) {
            case VERIFIED -> "All " + checks.stream().filter(c -> !c.status().equals(SKIP)).count() + " checks passed: every line accounted for, every statement outlined, every link resolves.";
            case WITH_WARNINGS -> "Structural checks passed; " + w + " warning(s): the code refers to things outside the scanned folder or the analysis is less certain in places.";
            default -> f + " check(s) failed — do not rely on the docs for the affected parts until they are fixed.";
        };
        Report r = new Report(verdict, summary, conf, rep.confidence.grade(), primaryFiles.size(), ctx.routineById.size(), checks);
        write(r);
        return r;
    }

    private void add(String id, String title, String status, String evidence, List<String> details) {
        checks.add(new Check(id, title, status, evidence, details));
    }

    private static int countUnknown(List<LogicStep> steps, List<String> sink, String id) {
        int n = 0;
        for (LogicStep s : steps) {
            if ("UNKNOWN".equals(s.kind)) { n++; sink.add(id + ": unmodelled statement at line " + s.line); }
            n += countUnknown(s.children, sink, id);
        }
        return n;
    }

    private List<String> brokenLinks() throws IOException {
        List<String> broken = new ArrayList<>();
        try (Stream<Path> s = Files.walk(out)) {
            for (Path md : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".md") && !out.relativize(p).startsWith("source"))::iterator) {
                boolean fenced = false;
                for (String line : Files.readAllLines(md)) {
                    if (line.startsWith("```") || line.startsWith("~~~")) { fenced = !fenced; continue; }
                    if (fenced) continue;
                    Matcher mt = LINK.matcher(line.replaceAll("`[^`]*`", ""));   // code spans (SQL, regexes) are not links
                    while (mt.find()) {
                        String t = mt.group(1);
                        if (t.contains("://") || t.startsWith("mailto:") || t.matches(".*[*?<>|\":].*")) continue;
                        Path target = md.getParent().resolve(t).normalize();
                        if (target.equals(out.resolve("analysis/verification.md").normalize()) || target.equals(out.resolve("analysis/verification.json").normalize())) continue; // written right after this check
                        if (!Files.exists(target)) broken.add(out.relativize(md).toString().replace('\\', '/') + " → " + t);
                    }
                }
            }
        }
        return broken;
    }

    private static byte[] sha(Path p) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
    }

    private void write(Report r) throws IOException {
        Path dir = out.resolve("analysis");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("verification.json"), new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(r));
        StringBuilder b = new StringBuilder("# Verification — did the tool understand this code?\n\n");
        b.append("**").append(r.verdict().replace('_', ' ')).append("** — ").append(r.summary()).append("\n\n");
        b.append("Parse confidence ").append(r.confidence()).append("/100 (").append(r.grade()).append("), ").append(r.files()).append(" files, ").append(r.routines()).append(" routines. ")
                .append("These checks re-read the written folder and compare it with the input files; they are not a statement about the quality of the PL/SQL itself.\n\n");
        b.append(Md.table(List.of("Check", "Result", "Evidence"), r.checks().stream().map(c -> List.of(c.title(), c.status(), c.evidence())).toList())).append('\n');
        for (Check c : r.checks()) {
            if (c.details().isEmpty() || c.status().equals(PASS)) continue;
            b.append("### ").append(c.title()).append(" — ").append(c.status()).append("\n\n").append(Md.list(c.details().stream().map(Md::code).toList())).append('\n');
        }
        b.append("See also [confidence.md](confidence.md) for the per-routine score and missing artifacts, and [file-dependencies.md](file-dependencies.md) for how the files relate.\n");
        Files.writeString(dir.resolve("verification.md"), b.toString());
    }
}
