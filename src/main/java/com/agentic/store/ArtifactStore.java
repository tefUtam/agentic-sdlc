package com.agentic.store;

import com.agentic.model.Artifact;
import com.agentic.model.ArtifactStatus;
import com.agentic.model.ArtifactType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Versioned, content-addressed artifact store. Rejected artifacts are kept for audit, never deleted. */
public final class ArtifactStore {
    private final Path dir;
    private final Map<String, Artifact> byId = new LinkedHashMap<>();
    private final Map<String, String> contents = new LinkedHashMap<>();

    public ArtifactStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Artifact commit(ArtifactType type, String content, String producedBy, int attempt, List<String> parents) {
        int version = (int) byId.values().stream().filter(a -> a.type() == type).count() + 1;
        String id = type + ":v" + version;
        Path path = dir.resolve(type + ".v" + version + "." + type.extension());
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Artifact a = new Artifact(id, type, version, Hashing.sha256(content), ArtifactStatus.DRAFT, producedBy, attempt,
                List.copyOf(parents), Instant.now(), path.toString());
        byId.put(id, a);
        contents.put(id, content);
        return a;
    }

    public synchronized Artifact setStatus(String id, ArtifactStatus status) {
        Artifact a = require(id).withStatus(status);
        byId.put(id, a);
        return a;
    }

    public synchronized Optional<Artifact> get(String id) { return Optional.ofNullable(byId.get(id)); }

    public synchronized Optional<Artifact> latestValid(ArtifactType type) {
        Artifact best = null;
        for (Artifact a : byId.values()) {
            if (a.type() == type && a.status() == ArtifactStatus.VALID && (best == null || a.version() > best.version())) best = a;
        }
        return Optional.ofNullable(best);
    }

    public synchronized String content(String id) {
        require(id);
        return contents.get(id);
    }

    public synchronized List<Artifact> all() { return List.copyOf(byId.values()); }

    /** All transitive ancestors of an artifact, nearest first. */
    public synchronized List<Artifact> lineage(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(require(id).parents());
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (seen.add(cur)) queue.addAll(require(cur).parents());
        }
        List<Artifact> out = new ArrayList<>();
        for (String s : seen) out.add(byId.get(s));
        return out;
    }

    private Artifact require(String id) {
        Artifact a = byId.get(id);
        if (a == null) throw new IllegalArgumentException("unknown artifact " + id);
        return a;
    }
}
