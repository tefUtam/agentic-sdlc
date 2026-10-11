package com.agentic.agent;

import com.agentic.model.ArtifactDraft;

import java.util.List;

/** The agent's self-assessment is advisory only. Gates and policies decide pass or fail. */
public record AgentResult(List<ArtifactDraft> outputs, List<AgentDecision> decisions, String selfAssessment) {}
