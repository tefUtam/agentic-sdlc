package com.agentic.engine;

import com.agentic.model.AgentRole;
import com.agentic.model.Decision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Append-only run context: the requirement plus every decision so far. Agents never see all of it;
 * {@link #scopedView} hands each role only what it needs.
 */
public final class RunContext {
    private final String runId;
    private final String requirement;
    private final List<Decision> decisions = new ArrayList<>();
    private int seq;

    public RunContext(String runId, String requirement) {
        this.runId = runId;
        this.requirement = requirement;
    }

    public String requirement() { return requirement; }

    public synchronized Decision addDecision(String nodeId, String kind, String rationale, String actor, List<String> refs) {
        Decision d = new Decision(String.format("D-%03d", ++seq), nodeId, kind, rationale, actor, List.copyOf(refs), Instant.now());
        decisions.add(d);
        return d;
    }

    public synchronized List<Decision> decisions() { return List.copyOf(decisions); }

    public synchronized Map<String, String> scopedView(AgentRole role) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("runId", runId);
        if (role == AgentRole.REQUIREMENTS || role == AgentRole.ARCHITECT) view.put("requirement", requirement);
        if (role == AgentRole.ARCHITECT || role == AgentRole.IMPLEMENTER || role == AgentRole.RELEASE) {
            view.put("decisions", decisions.stream()
                    .map(d -> d.id() + " [" + d.nodeId() + "] " + d.rationale())
                    .collect(Collectors.joining("\n")));
        }
        return view;
    }
}
