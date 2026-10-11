package com.agentic.model;

/** What an agent returns before the orchestrator has validated and committed it. */
public record ArtifactDraft(ArtifactType type, String content) {}
