package dev.sandeep.plsqlparser.explore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * M6: serves the Explorer UI (from jar resources) and the live contents of an {@code .agentdocs/} folder.
 * Nothing is cached or embedded: every request reads the folder again, and {@code /api/events} (server-sent events)
 * tells open pages when a rebuild changed it. Bound to 127.0.0.1 only.
 */
public final class ExplorerServer implements AutoCloseable {
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json; charset=utf-8", "md", "text/plain; charset=utf-8",
            "svg", "image/svg+xml");

    private final Path docs;
    private final HttpServer server;
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "explorer-watch");
        t.setDaemon(true);
        return t;
    });
    private final List<OutputStream> listeners = new CopyOnWriteArrayList<>();
    private volatile long stamp;

    private final BuildStatus status = new BuildStatus();
    private final List<OutputStream> progressListeners = new CopyOnWriteArrayList<>();

    /** The live feed of the build running next to this server (feed it via {@link dev.sandeep.plsqlparser.run.Progress}). */
    public BuildStatus status() {
        return status;
    }

    public ExplorerServer(Path docs, int port) throws IOException {
        this(docs, port, false);
    }

    /**
     * @param port        0 picks a free port
     * @param allowEmpty  accept a folder that has not been built yet (the live UI shows the build as it happens)
     */
    public ExplorerServer(Path docs, int port, boolean allowEmpty) throws IOException {
        this.docs = docs.toAbsolutePath().normalize();
        if (!allowEmpty && !Files.exists(this.docs.resolve("INDEX.json")))
            throw new IOException(this.docs + " is not an .agentdocs folder (no INDEX.json); run `build` first");
        status.onEvent(json -> broadcastTo(progressListeners, "data: " + json + "\n\n"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
    }

    /** Tries {@code first, first+1, ...} (at most {@code attempts} ports) and returns the first server that binds. */
    public static ExplorerServer startOnFirstFreePort(Path docs, int first, int attempts) throws IOException {
        return startOnFirstFreePort(docs, first, attempts, false);
    }

    public static ExplorerServer startOnFirstFreePort(Path docs, int first, int attempts, boolean allowEmpty) throws IOException {
        java.net.BindException last = null;
        for (int p = first; p < first + attempts && p <= 65535; p++) {
            try {
                return new ExplorerServer(docs, p, allowEmpty).start();
            } catch (java.net.BindException e) {
                last = e;
            }
        }
        throw new IOException("no free port in " + first + "-" + (first + attempts - 1), last);
    }

    public ExplorerServer start() {
        stamp = fingerprint();
        server.start();
        watcher.scheduleWithFixedDelay(this::poll, 700, 700, TimeUnit.MILLISECONDS);
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    @Override
    public void close() {
        watcher.shutdownNow();
        server.stop(0);
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange x) throws IOException {
        try {
            if (!x.getRequestMethod().equals("GET")) { send(x, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8)); return; }
            String path = x.getRequestURI().getPath();
            if (path.equals("/api/events")) { events(x); return; }
            if (path.equals("/api/status")) { send(x, 200, "application/json", status.snapshotJson().getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/api/progress")) { progress(x); return; }
            if (path.equals("/api/files")) { listFiles(x); return; }
            if (path.startsWith("/api/file/")) { file(x, decode(path.substring("/api/file/".length()))); return; }
            resource(x, path.equals("/") ? "index.html" : path.substring(1));
        } catch (RuntimeException e) {
            send(x, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Any file under the docs folder (cards, INDEX.json, analysis/graph.json, source/...) read fresh from disk. */
    private void file(HttpExchange x, String rel) throws IOException {
        Path p = docs.resolve(rel).normalize();
        if (rel.isEmpty() || rel.contains("\0") || !p.startsWith(docs) || !Files.isRegularFile(p)) {
            send(x, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(x, 200, type(rel), Files.readAllBytes(p));
    }

    private void listFiles(HttpExchange x) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(docs)) {
            s.filter(Files::isRegularFile).forEach(p -> out.add(docs.relativize(p).toString().replace('\\', '/')));
        }
        Collections.sort(out);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < out.size(); i++) b.append(i == 0 ? "" : ",").append('"').append(out.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        send(x, 200, "application/json", b.append(']').toString().getBytes(StandardCharsets.UTF_8));
    }

    private void resource(HttpExchange x, String name) throws IOException {
        if (name.contains("..") || name.startsWith("/")) { send(x, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8)); return; }
        try (InputStream in = ExplorerServer.class.getResourceAsStream("/explorer/" + name)) {
            if (in == null) { send(x, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8)); return; }
            send(x, 200, type(name), in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ live refresh

    private void events(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        os.write(("event: hello\ndata: " + stamp + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        listeners.add(os);
    }

    /** Server-sent events with every build event (stage, file, log, finished); the first message is a full snapshot. */
    private void progress(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        os.write(("data: {\"type\":\"snapshot\",\"snapshot\":" + status.snapshotJson() + "}\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        progressListeners.add(os);
    }

    private void broadcastTo(List<OutputStream> targets, String msg) {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : targets) {
            try {
                os.write(bytes);
                os.flush();
            } catch (IOException e) {
                targets.remove(os);
                try { os.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void poll() {
        long now = fingerprint();
        if (now == stamp) {
            broadcast(": keepalive\n\n");
            return;
        }
        stamp = now;
        broadcast("event: changed\ndata: " + now + "\n\n");
    }

    private void broadcast(String msg) {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : listeners) {
            try {
                os.write(bytes);
                os.flush();
            } catch (IOException e) {
                listeners.remove(os);
                try { os.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Cheap change detector: newest modification time plus file count under the folder. */
    private long fingerprint() {
        long newest = 0, count = 0;
        try (Stream<Path> s = Files.walk(docs)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                count++;
            }
        } catch (IOException | UncheckedIOException e) {
            return stamp; // mid-rebuild: keep the old value, check again next tick
        }
        return newest * 31 + count;
    }

    // ------------------------------------------------------------------ util

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String type(String name) {
        int i = name.lastIndexOf('.');
        return TYPES.getOrDefault(i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT), "text/plain; charset=utf-8");
    }

    private static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, body.length);
        try (OutputStream os = x.getResponseBody()) { os.write(body); }
    }
}
