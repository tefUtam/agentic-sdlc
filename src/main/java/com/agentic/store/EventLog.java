package com.agentic.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Append-only, hash-chained, thread-safe event log; mirrored to a JSONL file when a path is given. */
public final class EventLog {
    public static final String GENESIS = "0".repeat(64);

    private final List<Event> events = new ArrayList<>();
    private final Path file;
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private String lastHash = GENESIS;

    public EventLog(Path file) {
        this.file = file;
        if (file != null) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void addListener(Consumer<Event> l) { listeners.add(l); }

    public synchronized Event append(String type, String nodeId, String actor, Map<String, Object> payload) {
        long seq = events.size() + 1L;
        Instant ts = Instant.now();
        String payloadJson = Json.write(payload == null ? Map.of() : payload);
        String hash = Event.computeHash(lastHash, seq, ts, type, nodeId, actor, payloadJson);
        Event e = new Event(seq, ts, type, nodeId, actor, payloadJson, lastHash, hash);
        events.add(e);
        lastHash = hash;
        if (file != null) {
            try {
                Files.writeString(file, e.toJson() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        for (Consumer<Event> l : listeners) l.accept(e);
        return e;
    }

    public synchronized List<Event> events() { return List.copyOf(events); }

    public synchronized long count(String type) { return events.stream().filter(e -> e.type().equals(type)).count(); }

    public boolean verifyChain() { return verify(events()); }

    /** Recomputes the whole chain; false if any event was altered, removed, or reordered. */
    public static boolean verify(List<Event> list) {
        String prev = GENESIS;
        long expectedSeq = 1;
        for (Event e : list) {
            if (e.seq() != expectedSeq++ || !e.prevHash().equals(prev)) return false;
            String h = Event.computeHash(prev, e.seq(), e.ts(), e.type(), e.nodeId(), e.actor(), e.payloadJson());
            if (!h.equals(e.hash())) return false;
            prev = h;
        }
        return true;
    }
}
