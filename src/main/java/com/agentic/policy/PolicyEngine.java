package com.agentic.policy;

import com.agentic.store.EventLog;
import com.agentic.store.Json;

import java.util.List;

/** Evaluates applicable rules, logs every evaluation, and returns the most severe verdict. */
public final class PolicyEngine {
    private final List<PolicyRule> rules;
    private final EventLog log;

    public PolicyEngine(List<PolicyRule> rules, EventLog log) {
        this.rules = List.copyOf(rules);
        this.log = log;
    }

    public static List<PolicyRule> defaultRules() {
        return List.of(new SecretsRule(), new ReleaseGreenRule("RELEASE_APPROVE", 80.0));
    }

    public PolicyVerdict evaluate(PolicyContext ctx) {
        PolicyVerdict worst = PolicyVerdict.allow();
        for (PolicyRule rule : rules) {
            if (!rule.applies(ctx)) continue;
            PolicyVerdict v = rule.evaluate(ctx);
            log.append("POLICY_EVALUATED", ctx.node().id(), "policy-engine",
                    Json.obj("rule", rule.id(), "phase", ctx.phase(), "outcome", v.outcome(), "detail", v.detail()));
            if (v.outcome().compareTo(worst.outcome()) > 0) worst = v;
        }
        return worst;
    }
}
