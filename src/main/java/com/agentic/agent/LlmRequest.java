package com.agentic.agent;

import com.agentic.model.AgentRole;
import com.agentic.model.ArtifactType;

import java.util.Map;

public record LlmRequest(AgentRole role, ArtifactType target, Map<String, String> context,
                         Map<ArtifactType, String> inputs, String failureEvidence) {}
