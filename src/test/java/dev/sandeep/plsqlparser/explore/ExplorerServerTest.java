package dev.sandeep.plsqlparser.explore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExplorerServerTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Path docs(Path tmp) throws Exception {
        Path d = tmp.resolve(".agentdocs");
        Files.createDirectories(d.resolve("analysis"));
        Files.writeString(d.resolve("INDEX.json"), "{\"routines\":[]}");
        Files.writeString(d.resolve("analysis/graph.json"), "{\"nodes\":[]}");
        Files.writeString(tmp.resolve("secret.txt"), "outside the docs folder");
        return d;
    }

    private static HttpResponse<String> get(ExplorerServer s, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(s.url().replaceAll("/$", "") + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesFolderFilesFreshOnEveryRequest(@TempDir Path tmp) throws Exception {
        Path d = docs(tmp);
        try (ExplorerServer s = new ExplorerServer(d, 0).start()) {
            assertEquals("{\"nodes\":[]}", get(s, "/api/file/analysis/graph.json").body());
            Files.writeString(d.resolve("analysis/graph.json"), "{\"nodes\":[1]}");
            assertEquals("{\"nodes\":[1]}", get(s, "/api/file/analysis/graph.json").body(), "no caching: a rebuild shows on the next request");
            assertTrue(get(s, "/api/files").body().contains("\"analysis/graph.json\""));
        }
    }

    @Test
    void refusesPathTraversalAndMissingFiles(@TempDir Path tmp) throws Exception {
        Path d = docs(tmp);
        try (ExplorerServer s = new ExplorerServer(d, 0).start()) {
            assertEquals(404, get(s, "/api/file/..%2Fsecret.txt").statusCode());
            assertEquals(404, get(s, "/api/file/%2e%2e/secret.txt").statusCode());
            assertEquals(404, get(s, "/api/file/nope.md").statusCode());
            assertEquals(404, get(s, "/nope/..%2F..%2Fpom.xml").statusCode());
        }
    }

    @Test
    void liveFeedStatusAndEventsWorkBeforeAnythingIsBuilt(@TempDir Path tmp) throws Exception {
        Path empty = tmp.resolve(".agentdocs");   // does not exist yet: the UI is opened first, the build runs after
        try (ExplorerServer s = new ExplorerServer(empty, 0, true).start()) {
            assertTrue(get(s, "/api/status").body().contains("\"state\":\"idle\""));
            var res = HTTP.send(HttpRequest.newBuilder(URI.create(s.url() + "api/progress")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body()))) {
                assertTrue(r.readLine().startsWith("data: {\"type\":\"snapshot\""));
                r.readLine();
                s.status().start("test");
                s.status().stage("parse", "Parsing 2 files");
                s.status().file("a.pkb", 1, 2, 0);
                s.status().finished("VERIFIED", "ok");
                List<String> types = new java.util.ArrayList<>();
                String line;
                while (types.size() < 4 && (line = r.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(6));
                    types.add(n.get("type").asText());
                }
                assertEquals(List.of("start", "stage", "file", "finished"), types);
            }
            String snap = get(s, "/api/status").body();
            assertTrue(snap.contains("\"state\":\"done\"") && snap.contains("\"verdict\":\"VERIFIED\"") && snap.contains("a.pkb"), snap);
        }
    }

    @Test
    void rejectsAFolderThatIsNotAgentDocs(@TempDir Path tmp) {
        assertThrows(java.io.IOException.class, () -> new ExplorerServer(tmp, 0));
    }

    @Test
    void announcesRebuildsOverServerSentEvents(@TempDir Path tmp) throws Exception {
        Path d = docs(tmp);
        try (ExplorerServer s = new ExplorerServer(d, 0).start()) {
            var res = HTTP.send(HttpRequest.newBuilder(URI.create(s.url() + "api/events")).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body()))) {
                assertEquals("event: hello", r.readLine());
                Path g = d.resolve("analysis/graph.json");
                Files.writeString(g, "{\"nodes\":[2]}");
                Files.setLastModifiedTime(g, FileTime.fromMillis(System.currentTimeMillis() + 5000));
                String line;
                boolean changed = false;
                while ((line = r.readLine()) != null) if (line.equals("event: changed")) { changed = true; break; }
                assertTrue(changed);
            }
        }
    }
}
