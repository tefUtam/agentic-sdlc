package com.agentic.agent;

import com.agentic.model.AgentRole;
import com.agentic.model.Artifact;
import com.agentic.model.ArtifactType;

import java.util.List;
import java.util.Map;

/** Everything an agent is allowed to see. Immutable, so safe to hand to a worker thread. */
public record TaskSpec(String runId, String nodeId, AgentRole role, int attempt, Map<String, String> scopedContext,
                       List<Artifact> inputs, Map<ArtifactType, String> inputContent,
                       List<ArtifactType> expectedOutputs, String failureEvidence) {}
