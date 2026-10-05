package dev.sandeep.plsqlparser.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** End to end on a small multi-file project: the whole drop-in workflow (files, relations, ER, verdict, live progress). */
class PipelineTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SCHEMA = """
            create table customers (id number primary key, name varchar2(100) not null, email varchar2(200));
            create table orders (
              id number(10) not null,
              customer_id number not null,
              status varchar2(1),
              constraint pk_orders primary key (id),
              constraint fk_ord_cust foreign key (customer_id) references customers(id)
            );
            create table order_items (order_id number not null, line_no number not null, sku varchar2(30), primary key (order_id, line_no));
            alter table order_items add constraint fk_item_ord foreign key (order_id) references orders (id);
            create table shipments (id number primary key, order_ref number, carrier varchar2(30));
            """;
    static final String BILLING_SPEC = """
            create or replace package billing_pkg as
              procedure report;
            end billing_pkg;
            /
            """;
    static final String BILLING_BODY = """
            create or replace package body billing_pkg as
              procedure report is
              begin
                for r in (select o.id, s.carrier from orders o, shipments s where s.order_ref = o.id) loop
                  null;
                end loop;
              end;
            end billing_pkg;
            /
            """;
    static final String ORDERS_BODY = """
            create or replace package body orders_pkg as
              procedure close_all is
              begin
                update orders set status = 'C';
                billing_pkg.report;
                audit_pkg.note('closed');
              end;
            end orders_pkg;
            /
            """;
    static final String ORDERS_SPEC = """
            create or replace package orders_pkg as
              procedure close_all;
            end orders_pkg;
            /
            """;

    static Path project(Path tmp) throws Exception {
        Path in = tmp.resolve("project");
        Files.createDirectories(in.resolve("sub"));
        Files.writeString(in.resolve("schema.sql"), SCHEMA);
        Files.writeString(in.resolve("billing_pkg.pks"), BILLING_SPEC);
        Files.writeString(in.resolve("billing_pkg.pkb"), BILLING_BODY);
        Files.writeString(in.resolve("sub/orders_pkg.pks"), ORDERS_SPEC);
        Files.writeString(in.resolve("sub/orders_pkg.pkb"), ORDERS_BODY);
        Files.writeString(in.resolve("notes.txt"), "not plsql");
        Files.write(in.resolve("tool.jar"), new byte[]{1, 2, 3});
        return in;
    }

    private record Recorder(List<String> events) implements Progress {
        @Override public void stage(String id, String detail) { events.add("stage:" + id); }
        @Override public void file(String file, int done, int total, int errors) { events.add("file:" + file + ":" + total); }
        @Override public void log(String line) { }
        @Override public void finished(String verdict, String message) { events.add("finished:" + verdict); }
    }

    @Test
    void dropInRunWritesDocsNextToTheFilesAndVerifiesThem(@TempDir Path tmp) throws Exception {
        Path in = project(tmp), out = in.resolve(".agentdocs");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        var o = Pipeline.run(in, List.of(in), Map.of(), out, true, new Recorder(events));

        // live feed: stages in order, every PL/SQL file reported once, the jar and the txt are ignored
        List<String> stages = events.stream().filter(e -> e.startsWith("stage:")).toList();
        assertEquals(List.of("stage:discover", "stage:parse", "stage:analyse", "stage:write", "stage:verify"), stages);
        List<String> files = events.stream().filter(e -> e.startsWith("file:")).sorted().toList();
        assertEquals(5, files.size(), files.toString());
        assertTrue(files.stream().allMatch(f -> f.endsWith(":5")));
        assertFalse(files.toString().contains("tool.jar") || files.toString().contains("notes.txt"));
        assertTrue(events.get(events.size() - 1).startsWith("finished:"), events.toString());

        // output sits in the same folder, source copies keep the sub folder
        assertTrue(Files.exists(out.resolve("AGENTS.md")));
        assertTrue(Files.exists(out.resolve("source/sub/orders_pkg.pkb")));

        // cross-file relations: orders_pkg.pkb calls billing_pkg.pkb; specs pair with bodies; both touch ORDERS
        JsonNode fd = JSON.readTree(Files.readString(out.resolve("analysis/file-dependencies.json")));
        Set<String> edges = new TreeSet<>();
        for (JsonNode e : fd.get("edges")) edges.add(e.get("type").asText() + " " + e.get("from").asText() + " -> " + e.get("to").asText());
        assertTrue(edges.contains("CALLS sub/orders_pkg.pkb -> billing_pkg.pkb"), edges.toString());
        assertTrue(edges.contains("SPEC_BODY billing_pkg.pkb -> billing_pkg.pks"), edges.toString());
        assertTrue(edges.stream().anyMatch(e -> e.startsWith("SHARED_TABLE") && e.contains("billing_pkg.pkb") && e.contains("orders_pkg.pkb")), edges.toString());
        List<String> order = new ArrayList<>();
        fd.get("order").forEach(n -> order.add(n.asText()));
        assertTrue(order.indexOf("billing_pkg.pkb") < order.indexOf("sub/orders_pkg.pkb"), "callee file is read first: " + order);
        assertTrue(edges.contains("NEEDS_DDL billing_pkg.pkb -> schema.sql"), edges.toString());
        assertTrue(order.indexOf("schema.sql") < order.indexOf("billing_pkg.pkb"), "the file that creates the tables is read before the code that uses them: " + order);
        assertTrue(Files.readString(out.resolve("analysis/file-dependencies.md")).contains("flowchart LR"));

        // verdict: structure is sound, audit_pkg is outside the folder, so warnings, never a failure
        JsonNode v = JSON.readTree(Files.readString(out.resolve("analysis/verification.json")));
        assertEquals("VERIFIED_WITH_WARNINGS", v.get("verdict").asText(), v.toPrettyString());
        assertEquals(o.verdict(), v.get("verdict").asText());
        for (JsonNode c : v.get("checks")) if (!c.get("id").asText().equals("crossfile")) assertNotEquals("FAIL", c.get("status").asText(), c.toPrettyString());
        assertTrue(Files.readString(out.resolve("analysis/verification.md")).contains("audit_pkg".toUpperCase()));
    }

    @Test
    void entityRelationshipDiagramCombinesDdlAndJoins(@TempDir Path tmp) throws Exception {
        Path in = project(tmp), out = in.resolve(".agentdocs");
        Pipeline.run(in, List.of(in), Map.of(), out, true, Progress.NONE);
        JsonNode er = JSON.readTree(Files.readString(out.resolve("analysis/er.json")));
        Map<String, JsonNode> ent = new TreeMap<>();
        for (JsonNode e : er.get("entities")) ent.put(e.get("key").asText(), e);
        assertEquals(Set.of("CUSTOMERS", "ORDERS", "ORDER_ITEMS", "SHIPMENTS"), ent.keySet());
        assertTrue(ent.get("ORDERS").get("ddl").asBoolean());
        List<String> pk = new ArrayList<>();
        for (JsonNode c : ent.get("ORDER_ITEMS").get("columns")) if (c.path("pk").asBoolean()) pk.add(c.get("name").asText());
        assertEquals(List.of("ORDER_ID", "LINE_NO"), pk);

        Map<String, JsonNode> rel = new TreeMap<>();
        for (JsonNode r : er.get("relations")) rel.put(r.get("origin").asText() + " " + r.get("child").asText() + ">" + r.get("parent").asText(), r);
        assertTrue(rel.containsKey("DECLARED ORDERS>CUSTOMERS"), rel.keySet().toString());
        assertEquals("FK_ORD_CUST", rel.get("DECLARED ORDERS>CUSTOMERS").get("name").asText());
        assertTrue(rel.get("DECLARED ORDERS>CUSTOMERS").get("required").asBoolean());
        assertTrue(rel.containsKey("DECLARED ORDER_ITEMS>ORDERS"), "FK added by ALTER TABLE: " + rel.keySet());
        JsonNode inferred = rel.get("INFERRED SHIPMENTS>ORDERS");
        assertNotNull(inferred, "join s.order_ref = o.id gives an inferred many-to-one: " + rel.keySet());
        assertEquals("MANY_TO_ONE", inferred.get("cardinality").asText());
        assertEquals("ORDER_REF", inferred.get("childColumns").get(0).asText());

        String md = Files.readString(out.resolve("analysis/er-diagram.md"));
        assertTrue(md.contains("erDiagram") && md.contains("ORDERS }o--|| CUSTOMERS : \"FK_ORD_CUST\""), md);
        assertTrue(md.contains("SHIPMENTS }o--|o ORDERS : \"inferred join ORDER_REF = ID\""), md);
        assertTrue(md.contains("varchar2(100) NAME") && md.contains("number ORDER_ID PK, FK"), md);
    }

    @Test
    void syntaxErrorsMakeTheVerdictFailInsteadOfClaimingCompleteness(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("bad");
        Files.createDirectories(in);
        Files.writeString(in.resolve("bad.prc"), "create or replace procedure bad is begin if then end if end;\n/\n");
        var o = Pipeline.run(in, List.of(in), Map.of(), in.resolve(".agentdocs"), true, Progress.NONE);
        assertEquals("NOT_VERIFIED", o.verdict());
        JsonNode v = JSON.readTree(Files.readString(in.resolve(".agentdocs/analysis/verification.json")));
        boolean syntaxFailed = false;
        for (JsonNode c : v.get("checks")) syntaxFailed |= c.get("id").asText().equals("syntax") && c.get("status").asText().equals("FAIL");
        assertTrue(syntaxFailed, v.toPrettyString());
    }

    @Test
    void emptyFolderIsReportedNotSilentlySucceeding(@TempDir Path tmp) {
        List<String> events = new ArrayList<>();
        assertThrows(java.io.IOException.class, () -> Pipeline.run(tmp, List.of(tmp), Map.of(), tmp.resolve(".agentdocs"), true, new Recorder(events)));
        assertEquals("finished:null", events.get(events.size() - 1));
    }

    @Test
    void rerunReplacesThePreviousOutputAndIgnoresIt(@TempDir Path tmp) throws Exception {
        Path in = project(tmp), out = in.resolve(".agentdocs");
        Pipeline.run(in, List.of(in), Map.of(), out, true, Progress.NONE);
        var second = Pipeline.run(in, List.of(in), Map.of(), out, true, Progress.NONE);   // would double-count source/ copies if .agentdocs were scanned
        assertEquals(5, second.docs().sourceFiles());
    }
}
