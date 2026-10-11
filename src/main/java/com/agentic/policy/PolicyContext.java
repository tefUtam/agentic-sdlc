package com.agentic.policy;

import com.agentic.dag.Node;
import com.agentic.model.ArtifactDraft;
import com.agentic.model.ArtifactType;

import java.util.List;
import java.util.Map;

/** PRE_ACTION sees resolved inputs; POST_ACTION sees the agent's uncommitted outputs. */
public record PolicyContext(PolicyPhase phase, Node node, Map<ArtifactType, String> inputs, List<ArtifactDraft> outputs) {}
