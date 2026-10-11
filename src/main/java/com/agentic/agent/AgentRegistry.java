package com.agentic.agent;

import com.agentic.model.AgentRole;

import java.util.EnumMap;
import java.util.Map;

public final class AgentRegistry {
    private final Map<AgentRole, Agent> agents = new EnumMap<>(AgentRole.class);

    public AgentRegistry register(AgentRole role, Agent agent) {
        agents.put(role, agent);
        return this;
    }

    public static AgentRegistry withLlm(LlmClient llm) {
        AgentRegistry r = new AgentRegistry();
        Agent a = new LlmBackedAgent(llm);
        for (AgentRole role : AgentRole.values()) {
            if (role != AgentRole.HUMAN && role != AgentRole.NONE) r.register(role, a);
        }
        return r;
    }

    public Agent forRole(AgentRole role) {
        Agent a = agents.get(role);
        if (a == null) throw new IllegalStateException("no agent registered for role " + role);
        return a;
    }
}
