package com.agentic.agent;

/** The only non-deterministic edge of the system. Swap StubLlmClient for a real model client here. */
@FunctionalInterface
public interface LlmClient {
    LlmResponse complete(LlmRequest request) throws Exception;
}
