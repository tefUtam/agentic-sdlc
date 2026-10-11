package com.agentic.model;

import java.time.Instant;
import java.util.List;

/** Immutable, versioned, content-addressed artifact with lineage (parents). */
public record Artifact(String id, ArtifactType type, int version, String contentHash, ArtifactStatus status,
                       String producedBy, int attempt, List<String> parents, Instant createdAt, String path) {

    public Artifact withStatus(ArtifactStatus newStatus) {
        return new Artifact(id, type, version, contentHash, newStatus, producedBy, attempt, parents, createdAt, path);
    }
}
