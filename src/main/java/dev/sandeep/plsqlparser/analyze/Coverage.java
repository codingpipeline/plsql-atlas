package dev.sandeep.plsqlparser.analyze;

import dev.sandeep.plsqlparser.model.*;
import dev.sandeep.plsqlparser.model.StructureModel.SkippedItem;
import dev.sandeep.plsqlparser.parse.Preprocessor;

import java.util.*;
import java.util.function.Function;

/**
 * Proof that nothing was silently dropped: every line of code in every file must be accounted for by a recognised
 * unit, a listed skipped item, or a statement terminator; the rest is reported as unaccounted.
 */
public final class Coverage {
    public record FileCoverage(String file, int totalLines, int codeLines, int inUnits, int inSkipped, int terminators,
                               List<int[]> unaccounted) {
        public int unaccountedCount() {
            int n = 0;
            for (int[] r : unaccounted) n += r[1] - r[0] + 1;
            return n;
        }

        public double percent() {
            return codeLines == 0 ? 100.0 : 100.0 * (codeLines - unaccountedCount()) / codeLines;
        }
    }

    private Coverage() {}

    /** @param fileText original (decoded) text of a model file path */
    public static List<FileCoverage> compute(StructureModel m, Function<String, String> fileText) {
        Map<String, boolean[]> accounted = new LinkedHashMap<>();
        Map<String, List<String>> codeMask = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>(); // units, skipped, terminators
        Set<String> files = new LinkedHashSet<>();
        for (StructureModel.FileInfo fi : m.files) files.add(fi.file());
        for (String f : files) {
            String text = fileText.apply(f);
            String[] lines = Preprocessor.mask(text).split("\r?\n", -1);
            accounted.put(f, new boolean[lines.length + 2]);
            codeMask.put(f, Arrays.asList(lines));
            counts.put(f, new int[3]);
        }
        for (PlsqlUnit u : m.units) mark(accounted, counts, codeMask, u.file, u.line, u.endLine, 0);
        for (SkippedItem s : m.skipped) if (s.variant().equals("primary")) mark(accounted, counts, codeMask, s.file(), s.line(), s.endLine(), 1);

        List<FileCoverage> out = new ArrayList<>();
        for (String f : files) {
            List<String> lines = codeMask.get(f);
            boolean[] acc = accounted.get(f);
            int code = 0, term = 0;
            List<int[]> missing = new ArrayList<>();
            for (int i = 1; i <= lines.size(); i++) {
                String t = lines.get(i - 1).strip();
                if (t.isEmpty()) continue;
                code++;
                if (acc[i]) continue;
                if (t.equals("/") || t.equals(";")) { term++; continue; }
                if (!missing.isEmpty() && missing.get(missing.size() - 1)[1] == i - 1) missing.get(missing.size() - 1)[1] = i;
                else missing.add(new int[]{i, i});
            }
            int[] c = counts.get(f);
            out.add(new FileCoverage(f, lines.size(), code, c[0], c[1], term, missing));
        }
        return out;
    }

    private static void mark(Map<String, boolean[]> acc, Map<String, int[]> counts, Map<String, List<String>> masks, String file,
                             int from, int to, int bucket) {
        boolean[] a = acc.get(file);
        if (a == null) return;
        List<String> lines = masks.get(file);
        for (int i = from; i <= to && i < a.length; i++) {
            if (a[i]) continue;
            a[i] = true;
            if (i - 1 < lines.size() && !lines.get(i - 1).isBlank()) counts.get(file)[bucket]++;
        }
    }
}
