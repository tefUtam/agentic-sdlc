package com.agentic.gate;

/** A deterministic check. Must be a pure function of the context. */
@FunctionalInterface
public interface GateCheck {
    GateResult evaluate(GateContext ctx);
}
