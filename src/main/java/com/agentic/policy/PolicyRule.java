package com.agentic.policy;

/** Declarative guardrail. Rules that do not apply are not evaluated or logged. */
public interface PolicyRule {
    String id();

    boolean applies(PolicyContext ctx);

    PolicyVerdict evaluate(PolicyContext ctx);
}
