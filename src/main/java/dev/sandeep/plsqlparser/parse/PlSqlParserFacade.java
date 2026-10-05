package dev.sandeep.plsqlparser.parse;

import dev.sandeep.plsqlparser.grammar.PlSqlLexer;
import dev.sandeep.plsqlparser.grammar.PlSqlParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Reads files, resolves $IF variants and parses each variant with the ANTLR PL/SQL grammar.
 * Files are parsed in parallel; SLL prediction first, full LL only when SLL reports an error.
 */
public final class PlSqlParserFacade {
    public record SyntaxIssue(int line, int column, String message) {}

    /** One parsed variant of one source file. */
    public record ParsedUnit(Path path, String variant, String source, int lines, List<SyntaxIssue> errors,
                             PlSqlParser.Sql_scriptContext tree, CommonTokenStream tokens, PlSqlParser parser,
                             List<Preprocessor.Condition> conditions) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private PlSqlParserFacade() {}

    public static String read(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        String s;
        try {
            s = dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            s = new String(raw, Charset.forName("windows-1252"));
        }
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    public static List<ParsedUnit> parseFiles(List<Path> files, Map<String, Object> flags, int[] target) {
        return parseFiles(files, null, flags, target);
    }

    /** @param root if given, file paths in the result are relative to it (so docs can cite {@code source/<path>}) */
    public static List<ParsedUnit> parseFiles(List<Path> files, Path root, Map<String, Object> flags, int[] target) {
        return parseFiles(files, root, flags, target, null);
    }

    /** Called after each file (all its variants) is parsed; may run on worker threads. */
    public interface FileDone {
        void done(String shownPath, int errorsInPrimary);
    }

    public static List<ParsedUnit> parseFiles(List<Path> files, Path root, Map<String, Object> flags, int[] target, FileDone onFile) {
        Path base = root == null ? null : root.toAbsolutePath().normalize();
        List<ParsedUnit> all = Collections.synchronizedList(new ArrayList<>());
        files.parallelStream().forEach(f -> {
            try {
                Path abs = f.toAbsolutePath().normalize();
                Path shown = base != null && abs.startsWith(base) ? base.relativize(abs) : f;
                int errors = 0;
                for (Preprocessor.Variant v : Preprocessor.preprocess(read(f), flags, target)) {
                    ParsedUnit pu = parseText(shown, v.name(), v.text(), v.conditions());
                    if (v.name().equals("primary")) errors = pu.errors().size();
                    all.add(pu);
                }
                if (onFile != null) onFile.done(shown.toString().replace('\\', '/'), errors);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        all.sort(Comparator.comparing((ParsedUnit u) -> u.path().toString()).thenComparing(ParsedUnit::variant));
        return all;
    }

    public static ParsedUnit parseText(Path path, String variant, String text, List<Preprocessor.Condition> conds) {
        List<SyntaxIssue> errors = new ArrayList<>();
        BaseErrorListener collector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object o, int line, int col, String msg, RecognitionException e) {
                errors.add(new SyntaxIssue(line, col, msg.length() > 300 ? msg.substring(0, 300) : msg));
            }
        };
        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collector);
        CommonTokenStream ts = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(ts);
        parser.removeErrorListeners();
        parser.addErrorListener(collector);
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        PlSqlParser.Sql_scriptContext tree = parser.sql_script();
        if (!errors.isEmpty()) {
            errors.clear();
            lexer.reset();
            ts.seek(0);
            parser.reset();
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            tree = parser.sql_script();
        }
        int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
        return new ParsedUnit(path, variant, text, lines, List.copyOf(errors), tree, ts, parser, conds);
    }
}
