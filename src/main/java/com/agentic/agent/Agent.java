package com.agentic.agent;

/** Agents are stateless: all state lives in the run context and artifacts. */
public interface Agent {
    AgentResult execute(TaskSpec task) throws Exception;
}
