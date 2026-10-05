package dev.sandeep.plsqlparser.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MainRoutingTest {
    @Test
    void noArgumentsMeansGoInTheCurrentFolder() {
        assertArrayEquals(new String[]{"go"}, Main.route(new String[0]));
    }

    @Test
    void aBarePathMeansGoOnThatPath(@TempDir Path dir) {
        assertArrayEquals(new String[]{"go", dir.toString(), "--no-ui"}, Main.route(new String[]{dir.toString(), "--no-ui"}));
    }

    @Test
    void goOptionsAsFirstArgumentMeanGo() {
        assertArrayEquals(new String[]{"go", "--no-open", "--watch"}, Main.route(new String[]{"--no-open", "--watch"}));
        assertArrayEquals(new String[]{"go", "-D", "x=1"}, Main.route(new String[]{"-D", "x=1"}));
    }

    @Test
    void subcommandsAndFlagsAreLeftAlone() {
        assertArrayEquals(new String[]{"build", "x"}, Main.route(new String[]{"build", "x"}));
        assertArrayEquals(new String[]{"--help"}, Main.route(new String[]{"--help"}));
        assertArrayEquals(new String[]{"--version"}, Main.route(new String[]{"--version"}));
        assertArrayEquals(new String[]{"explore", "."}, Main.route(new String[]{"explore", "."}));
        assertArrayEquals(new String[]{"nonexistent-thing"}, Main.route(new String[]{"nonexistent-thing"}));
    }
}
