package com.agentic.policy;

public record PolicyVerdict(PolicyOutcome outcome, String ruleId, String detail) {
    public static PolicyVerdict allow() { return new PolicyVerdict(PolicyOutcome.ALLOW, "-", "no rule objected"); }
    public static PolicyVerdict allow(String ruleId, String detail) { return new PolicyVerdict(PolicyOutcome.ALLOW, ruleId, detail); }
    public static PolicyVerdict requireApproval(String ruleId, String detail) { return new PolicyVerdict(PolicyOutcome.REQUIRE_APPROVAL, ruleId, detail); }
    public static PolicyVerdict deny(String ruleId, String detail) { return new PolicyVerdict(PolicyOutcome.DENY, ruleId, detail); }
}
