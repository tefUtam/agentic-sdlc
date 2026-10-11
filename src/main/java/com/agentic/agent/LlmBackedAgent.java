package com.agentic.agent;

import com.agentic.model.ArtifactDraft;
import com.agentic.model.ArtifactType;

import java.util.ArrayList;
import java.util.List;

/** Generic role agent: asks the LLM client for each artifact type the node must produce. */
public final class LlmBackedAgent implements Agent {
    private final LlmClient llm;

    public LlmBackedAgent(LlmClient llm) { this.llm = llm; }

    @Override
    public AgentResult execute(TaskSpec task) throws Exception {
        List<ArtifactDraft> outputs = new ArrayList<>();
        List<AgentDecision> decisions = new ArrayList<>();
        for (ArtifactType target : task.expectedOutputs()) {
            LlmResponse r = llm.complete(new LlmRequest(task.role(), target, task.scopedContext(),
                    task.inputContent(), task.failureEvidence()));
            outputs.add(new ArtifactDraft(target, r.content()));
            if (r.rationale() != null && !r.rationale().isBlank()) decisions.add(new AgentDecision(target, r.rationale()));
        }
        return new AgentResult(outputs, decisions, "generated " + outputs.size() + " artifact(s)");
    }
}
