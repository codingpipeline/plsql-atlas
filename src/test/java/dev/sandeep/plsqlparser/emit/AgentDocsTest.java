package dev.sandeep.plsqlparser.emit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sandeep.plsqlparser.extract.StructureBuilder;
import dev.sandeep.plsqlparser.model.StructureModel;
import dev.sandeep.plsqlparser.parse.Discover;
import dev.sandeep.plsqlparser.parse.PlSqlParserFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentDocsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#\\s]+)(?:#[^)]*)?\\)");

    private static final String PKG_SPEC = """
            create or replace package orders_pkg as
              -- places an order and returns its id
              function place(p_customer in number, p_qty in number) return number;
              procedure cancel(p_id in number);
            end orders_pkg;
            /
            """;
    private static final String PKG_BODY = """
            create or replace package body orders_pkg as
              g_last_id number;
              function next_id return number is
                l_id number;
              begin
                select orders_seq.nextval into l_id from dual;
                return l_id;
              end;
              function place(p_customer in number, p_qty in number) return number is
                l_id number := next_id;
              begin
                if p_qty <= 0 then
                  raise_application_error(-20001, 'bad quantity');
                end if;
                insert into orders (id, customer_id, qty) values (l_id, p_customer, p_qty);
                g_last_id := l_id;
                return l_id;
              exception
                when dup_val_on_index then
                  return null;
              end;
              procedure cancel(p_id in number) is
              begin
                update orders set status = 'X' where id = p_id;
                audit_pkg.log('cancel ' || p_id);
                commit;
              end;
            end orders_pkg;
            /
            """;
    private static final String STANDALONE = """
            create or replace procedure report_orders is
            begin
              for r in (select id, qty from orders) loop
                dbms_output.put_line(r.id || ' ' || r.qty);
              end loop;
            end;
            /
            create or replace trigger orders_bi before insert on orders for each row
            begin
              :new.created := sysdate;
            end;
            /
            create table audit_t (id number);
            """;

    private static Path fixture(Path dir) throws IOException {
        Path in = dir.resolve("input");
        Files.createDirectories(in);
        Files.writeString(in.resolve("orders_pkg.pks"), PKG_SPEC);
        Files.writeString(in.resolve("orders_pkg.pkb"), PKG_BODY);
        Files.writeString(in.resolve("misc.sql"), STANDALONE);
        return in;
    }

    private static AgentDocs.Result build(Path in, Path out) throws IOException {
        StructureModel m = StructureBuilder.build(PlSqlParserFacade.parseFiles(Discover.files(in), in, Map.of(), new int[]{19, 0}));
        return AgentDocs.write(m, in, out, true);
    }

    private static List<Path> allFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void writesTheDocumentedLayout(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        AgentDocs.Result r = build(in, out);
        for (String f : List.of("AGENTS.md", "OVERVIEW.md", "INDEX.json", "analysis/migration-order.md", "analysis/risks.md", "analysis/coverage.md",
                "objects/orders_pkg/_package.md", "objects/orders_pkg/place.md", "objects/orders_pkg/place.json", "objects/orders_pkg/cancel.md",
                "objects/_standalone/report_orders.md", "objects/_triggers/orders_bi.md", "tables/ORDERS.md", "source/orders_pkg.pkb"))
            assertTrue(Files.exists(out.resolve(f)), f + " missing; have " + allFiles(out).stream().map(p -> out.relativize(p).toString()).toList());
        assertEquals(0, r.unaccountedLines());

        String card = Files.readString(out.resolve("objects/orders_pkg/place.md"));
        for (String section : List.of("## Purpose", "## Signature", "## Logic outline", "## SQL statements", "## Calls", "## State & side effects",
                "## Exception contract", "## Oracle semantics notes", "## Risks", "## Open questions", "## Source"))
            assertTrue(card.contains(section), section + " missing in card");
        assertTrue(card.contains("places an order and returns its id"), "spec doc comment becomes the purpose");
        assertTrue(card.contains("RAISE_APPLICATION_ERROR") && card.contains("-20001"));
        assertTrue(card.contains("writes package state: `g_last_id`"));
        assertTrue(card.contains("dup_val_on_index"));
        assertTrue(card.contains("INSERT [ORDERS](../../tables/ORDERS.md)"), card);
        assertTrue(card.contains("orders_pkg.pkb:"), "cites root-relative source path");

        JsonNode j = JSON.readTree(Files.readString(out.resolve("objects/orders_pkg/place.json")));
        assertEquals("ORDERS_PKG.PLACE", j.get("id").asText());
        assertTrue(j.get("source").asText().contains("raise_application_error"));
        assertTrue(j.get("outline").size() > 0);
        assertEquals("ORDERS", j.get("tablesWritten").get(0).asText());
    }

    @Test
    void graphJsonFeedsTheExplorer(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        JsonNode g = JSON.readTree(Files.readString(out.resolve("analysis/graph.json")));
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode n : g.get("nodes")) byId.put(n.get("id").asText(), n);
        JsonNode place = byId.get("ORDERS_PKG.PLACE");
        assertNotNull(place, byId.keySet().toString());
        assertTrue(place.get("cyclomatic").asInt() >= 2);
        assertEquals("objects/orders_pkg/place.json", place.get("cardJson").asText());
        assertEquals("TABLE", byId.get("TABLE:ORDERS").get("type").asText());
        boolean writes = false;
        for (JsonNode e : g.get("edges")) writes |= e.get("from").asText().equals("ORDERS_PKG.PLACE")
                && e.get("to").asText().equals("TABLE:ORDERS") && e.get("type").asText().equals("WRITES");
        assertTrue(writes, "PLACE writes ORDERS edge");
        assertTrue(g.get("tableImpact").has("ORDERS"));
        assertTrue(g.get("coverage").size() > 0);
    }

    @Test
    void confidenceScoresRoutinesAndListsMissingArtifacts(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        JsonNode c = JSON.readTree(Files.readString(out.resolve("analysis/confidence.json")));
        assertTrue(c.get("overall").asInt() > 0 && c.get("overall").asInt() < 100, "audit_pkg is not scanned, so not perfect: " + c.get("overall"));
        Map<String, JsonNode> score = new HashMap<>();
        for (JsonNode r : c.get("routines")) score.put(r.get("id").asText(), r);
        assertEquals(100, score.get("ORDERS_PKG.PLACE").get("score").asInt());
        assertTrue(score.get("ORDERS_PKG.CANCEL").get("score").asInt() < 100, "calls the unscanned AUDIT_PKG");
        assertEquals("EXTERNAL_CALL", score.get("ORDERS_PKG.CANCEL").get("findings").get(0).get("code").asText());
        Set<String> missing = new HashSet<>();
        for (JsonNode x : c.get("missing")) missing.add(x.get("kind").asText() + ":" + x.get("name").asText());
        assertTrue(missing.contains("EXTERNAL_PACKAGE:AUDIT_PKG"), missing.toString());
        assertTrue(missing.contains("TABLE_DDL_MISSING:ORDERS"), missing.toString());
        assertTrue(Files.readString(out.resolve("analysis/confidence.md")).contains("AUDIT_PKG"));
        assertTrue(Files.readString(out.resolve("AGENTS.md")).contains("Parse confidence"));
        JsonNode g = JSON.readTree(Files.readString(out.resolve("analysis/graph.json")));
        for (JsonNode n : g.get("nodes")) if (n.get("id").asText().equals("ORDERS_PKG.CANCEL")) assertEquals(score.get("ORDERS_PKG.CANCEL").get("score").asInt(), n.get("confidence").asInt());
    }

    @Test
    void indexListsEveryRoutineAndCardsExist(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        JsonNode idx = JSON.readTree(Files.readString(out.resolve("INDEX.json")));
        Set<String> ids = new TreeSet<>();
        for (JsonNode r : idx.get("routines")) {
            ids.add(r.get("id").asText());
            assertTrue(Files.exists(out.resolve(r.get("card").asText())), r.get("card").asText());
            assertTrue(Files.exists(out.resolve(r.get("cardJson").asText())));
        }
        assertTrue(ids.containsAll(Set.of("ORDERS_PKG.PLACE", "ORDERS_PKG.CANCEL", "ORDERS_PKG.NEXT_ID", "REPORT_ORDERS")), ids.toString());
    }

    @Test
    void calleesAreOrderedBeforeCallers(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        Map<String, Integer> batch = new HashMap<>();
        for (JsonNode r : JSON.readTree(Files.readString(out.resolve("INDEX.json"))).get("routines")) batch.put(r.get("id").asText(), r.get("batch").asInt());
        assertTrue(batch.get("ORDERS_PKG.NEXT_ID") < batch.get("ORDERS_PKG.PLACE"));
    }

    @Test
    void everyRelativeLinkResolves(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        assertLinksResolve(out);
    }

    private static void assertLinksResolve(Path out) throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path f : allFiles(out)) {
            if (!f.toString().endsWith(".md") || out.relativize(f).startsWith("source")) continue;
            String text = Files.readString(f).replaceAll("(?s)(```|~~~~).*?\\1", "").replaceAll("`[^`\\n]*`", ""); // links inside code are not links
            Matcher m = LINK.matcher(text);
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http") || target.startsWith("mailto")) continue;
                if (!Files.exists(f.getParent().resolve(target).normalize())) broken.add(out.relativize(f) + " -> " + target);
            }
        }
        assertTrue(broken.isEmpty(), () -> broken.size() + " broken links, e.g. " + broken.subList(0, Math.min(5, broken.size())));
    }

    @Test
    void outputIsDeterministicAndReplacesPreviousRun(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        Map<String, String> first = snapshot(out);
        Files.writeString(out.resolve("stale.md"), "left over");
        build(in, out);
        Map<String, String> second = snapshot(out);
        assertEquals(first, second, "same input must give byte-identical docs, and stale files must be removed");
    }

    private static Map<String, String> snapshot(Path out) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (Path f : allFiles(out)) m.put(out.relativize(f).toString().replace('\\', '/'), Files.readString(f));
        return m;
    }

    @Test
    void refusesToWipeAFolderThatIsNotAPreviousOutput(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("precious");
        Files.createDirectories(out);
        Files.writeString(out.resolve("my-notes.txt"), "do not delete");
        assertThrows(IOException.class, () -> build(in, out));
        assertTrue(Files.exists(out.resolve("my-notes.txt")));
    }

    @Test
    void tableAndPackagePagesCarryTheFacts(@TempDir Path tmp) throws Exception {
        Path in = fixture(tmp), out = tmp.resolve("docs");
        build(in, out);
        String table = Files.readString(out.resolve("tables/ORDERS.md"));
        assertTrue(table.contains("INSERT") && table.contains("UPDATE") && table.contains("orders_bi"), table);
        assertTrue(table.contains("customer_id") && table.contains("status"));
        String pkg = Files.readString(out.resolve("objects/orders_pkg/_package.md"));
        assertTrue(pkg.contains("Public routines") && pkg.contains("**state variable** `g_last_id`") && pkg.contains("AUDIT_PKG") || pkg.contains("audit_pkg"), pkg);
        String agents = Files.readString(out.resolve("AGENTS.md"));
        assertTrue(agents.contains("Reading order") && agents.contains("lossless"));
    }

    @Test
    void realWorldCorpusProducesCompleteLinkedDocs(@TempDir Path tmp) throws Exception {
        Path in = Path.of("corpus", "real");
        assumeTrue(Files.isDirectory(in));
        Path out = tmp.resolve("docs");
        AgentDocs.Result r = build(in, out);
        assertEquals(0, r.unaccountedLines());
        assertTrue(r.cards() > 100);
        assertLinksResolve(out);
        String cov = Files.readString(out.resolve("analysis/coverage.md"));
        assertTrue(cov.contains("every line of code is accounted for"));
        assertTrue(cov.contains("**0 mismatching**"), "round-trip check must find no mismatching routine source spans");
    }
}
