package dev.sandeep.plsqlparser.explore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sandeep.plsqlparser.run.Progress;

import java.util.*;
import java.util.function.Consumer;

/**
 * What the current (or last) build is doing, for the live UI. Purely transient: it holds no project data beyond file names and
 * progress, and everything durable is read from the output folder.
 */
public final class BuildStatus implements Progress {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOG = 400;

    private final Object lock = new Object();
    private String state = "idle"; // idle | building | done | failed
    private int run;
    private long startedAt, endedAt;
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private final Map<String, Map<String, Object>> files = new LinkedHashMap<>();
    private final Deque<String> log = new ArrayDeque<>();
    private int total, done;
    private String verdict, message;
    private volatile Consumer<String> sink = s -> { };

    void onEvent(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Begin a new run (clears the previous one). */
    public void start(String what) {
        synchronized (lock) {
            state = "building";
            run++;
            startedAt = System.currentTimeMillis();
            endedAt = 0;
            stages.clear();
            files.clear();
            log.clear();
            total = done = 0;
            verdict = message = null;
        }
        emit(Map.of("type", "start", "run", run, "what", what));
    }

    @Override
    public void stage(String id, String detail) {
        synchronized (lock) {
            if (!stages.isEmpty()) stages.get(stages.size() - 1).put("status", "done");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("detail", detail);
            s.put("status", "running");
            s.put("at", System.currentTimeMillis() - startedAt);
            stages.add(s);
        }
        emit(Map.of("type", "stage", "id", id, "detail", detail));
    }

    @Override
    public void file(String file, int done, int total, int syntaxErrors) {
        synchronized (lock) {
            this.total = total;
            this.done = Math.max(this.done, done);
            files.put(file, Map.of("file", file, "errors", syntaxErrors));
        }
        emit(Map.of("type", "file", "file", file, "done", done, "total", total, "errors", syntaxErrors));
    }

    @Override
    public void log(String line) {
        synchronized (lock) {
            log.addLast(line);
            while (log.size() > MAX_LOG) log.removeFirst();
        }
        emit(Map.of("type", "log", "line", line));
    }

    @Override
    public void finished(String verdict, String message) {
        synchronized (lock) {
            if (!stages.isEmpty()) stages.get(stages.size() - 1).put("status", verdict == null ? "failed" : "done");
            this.verdict = verdict;
            this.message = message;
            state = verdict == null ? "failed" : "done";
            endedAt = System.currentTimeMillis();
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "finished");
        e.put("state", state);
        e.put("verdict", verdict);
        e.put("message", message);
        emit(e);
    }

    public String state() {
        synchronized (lock) { return state; }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("run", run);
            m.put("elapsedMs", (endedAt == 0 ? (startedAt == 0 ? 0 : System.currentTimeMillis()) : endedAt) - startedAt);
            m.put("stages", new ArrayList<>(stages));
            m.put("files", new ArrayList<>(files.values()));
            m.put("total", total);
            m.put("done", done);
            m.put("log", new ArrayList<>(log));
            m.put("verdict", verdict);
            m.put("message", message);
            return m;
        }
    }

    public String snapshotJson() {
        try {
            return JSON.writeValueAsString(snapshot());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void emit(Map<String, ?> event) {
        try {
            sink.accept(JSON.writeValueAsString(event));
        } catch (JsonProcessingException ignored) {
            // an unserialisable event only costs one live-feed line
        }
    }
}
