package com.agentic.agent;

import com.agentic.model.ArtifactType;

/** A choice an agent reports about one of its outputs; the orchestrator turns it into a lineage-linked Decision. */
public record AgentDecision(ArtifactType about, String rationale) {}
