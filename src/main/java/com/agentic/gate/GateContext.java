package com.agentic.gate;

import com.agentic.model.Artifact;
import com.agentic.model.ArtifactType;

import java.util.List;
import java.util.Map;

/**
 * Everything a check may look at. Entry gates see resolved inputs; exit gates also see the
 * agent's (not yet trusted) outputs.
 */
public record GateContext(String nodeId, List<ArtifactType> requiredInputs, List<Artifact> inputs,
                          List<ArtifactType> expectedOutputs, Map<ArtifactType, String> outputs) {}
