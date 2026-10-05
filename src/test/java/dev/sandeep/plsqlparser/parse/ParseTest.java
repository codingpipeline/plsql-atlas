package dev.sandeep.plsqlparser.parse;

import dev.sandeep.plsqlparser.parse.PlSqlParserFacade.ParsedUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ParseTest {
    private static final int[] TARGET = {19, 0};

    @Test
    void conditionalCompilationIsLinePreservingWithTwoVariants() {
        String text = "begin\r\n$if $$flag $then\r\n  a := 1;\r\n$else\r\n  a := 2;\r\n$end\r\nend;\r\n";
        List<Preprocessor.Variant> vs = Preprocessor.preprocess(text, Map.of(), TARGET);
        assertEquals(List.of("primary", "alternate"), vs.stream().map(Preprocessor.Variant::name).toList());
        for (var v : vs) assertEquals(text.chars().filter(c -> c == '\n').count(), v.text().chars().filter(c -> c == '\n').count());
        assertTrue(vs.get(0).text().contains("a := 2") && !vs.get(0).text().contains("a := 1"));
        assertTrue(vs.get(1).text().contains("a := 1") && !vs.get(1).text().contains("a := 2"));
    }

    @Test
    void decidedConditionGivesSingleVariant() {
        var vs = Preprocessor.preprocess("$if dbms_db_version.ver_le_10 $then\nx;\n$else\ny;\n$end\n", Map.of(), TARGET);
        assertEquals(1, vs.size());
        assertTrue(vs.get(0).text().contains("y;") && !vs.get(0).text().contains("x;"));
    }

    @Test
    void undefinedFlagIsNullLikeOracleAndAlternateSetsItTrue() {
        String text = "$if $$no_op is null or not $$no_op $then\ndecl;\n$end\n$if $$no_op $then\nstub;\n$else\nreal;\n$end\n";
        var vs = Preprocessor.preprocess(text, Map.of(), TARGET);
        assertEquals(2, vs.size());
        assertTrue(vs.get(0).text().contains("decl;") && vs.get(0).text().contains("real;") && !vs.get(0).text().contains("stub;"));
        assertTrue(vs.get(1).text().contains("stub;") && !vs.get(1).text().contains("decl;") && !vs.get(1).text().contains("real;"));
        assertTrue(vs.get(0).conditions().stream().noneMatch(Preprocessor.Condition::decided), "both depend on an undefined flag");
        // once the flag is defined there is a single, fully decided variant
        var defined = Preprocessor.preprocess(text, Map.of("no_op", Boolean.FALSE), TARGET);
        assertEquals(1, defined.size());
        assertTrue(defined.get(0).text().contains("decl;") && defined.get(0).text().contains("real;"));
    }

    @Test
    void directivesInsideCommentsAndStringsAreIgnored() {
        String text = "-- $if x $then\nselect '$end' from dual;\n";
        assertEquals(text, Preprocessor.preprocess(text, Map.of(), TARGET).get(0).text());
    }

    @Test
    void parsesSmallPackage() {
        String src = """
                create or replace package demo_pkg as
                  function f(p_x in number) return number;
                end demo_pkg;
                /
                create or replace package body demo_pkg as
                  function f(p_x in number) return number is begin return p_x + 1; end;
                end demo_pkg;
                /
                """;
        ParsedUnit u = PlSqlParserFacade.parseText(Path.of("demo.sql"), "primary", src, List.of());
        assertTrue(u.ok(), () -> u.errors().toString());
    }

    @Test
    void realWorldCorpusParsesWithoutSyntaxErrors() throws Exception {
        Path corpus = Path.of("corpus", "real");
        assumeTrue(Files.isDirectory(corpus), "run scripts/fetch-real-world-corpus first");
        List<Path> files = Discover.files(corpus);
        assumeTrue(!files.isEmpty());
        List<String> bad = new ArrayList<>();
        for (ParsedUnit u : PlSqlParserFacade.parseFiles(files, Map.of(), TARGET))
            if (!u.ok()) bad.add(u.path() + " [" + u.variant() + "] " + u.errors().get(0));
        assertTrue(bad.isEmpty(), bad::toString);
    }
}
