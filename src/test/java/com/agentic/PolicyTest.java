package com.agentic;

import com.agentic.dag.Node;
import com.agentic.model.ArtifactDraft;
import com.agentic.model.ArtifactType;
import com.agentic.policy.PolicyContext;
import com.agentic.policy.PolicyEngine;
import com.agentic.policy.PolicyOutcome;
import com.agentic.policy.PolicyPhase;
import com.agentic.policy.PolicyRule;
import com.agentic.policy.PolicyVerdict;
import com.agentic.policy.ReleaseGreenRule;
import com.agentic.policy.SecretsRule;
import com.agentic.store.EventLog;

import java.util.List;
import java.util.Map;

import static com.agentic.model.AgentRole.*;
import static com.agentic.model.ArtifactType.*;
import static com.agentic.model.RiskTier.*;

public class PolicyTest {
    private static final Node NODE = Node.agent("X", IMPLEMENTER, LOW);

    private static PolicyContext post(String content) {
        return new PolicyContext(PolicyPhase.POST_ACTION, NODE, Map.of(), List.of(new ArtifactDraft(CODE_CORE, content)));
    }

    public void testSecretsRuleDeniesCommonSecretShapes() {
        SecretsRule r = new SecretsRule();
        Assert.eq(PolicyOutcome.DENY, r.evaluate(post("key = AKIAABCDEFGHIJKLMNOP")).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(post("-----BEGIN RSA PRIVATE KEY-----\nabc")).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(post("db.password = \"hunter2hunter2\"")).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(post("API_KEY: abcdef123456")).outcome());
    }

    public void testSecretsRuleAllowsOrdinaryContent() {
        SecretsRule r = new SecretsRule();
        Assert.eq(PolicyOutcome.ALLOW, r.evaluate(post("package com.x; // password policy documented elsewhere")).outcome());
        Assert.eq(PolicyOutcome.ALLOW, r.evaluate(post("token bucket rate limiter")).outcome());
    }

    public void testSecretsRuleOnlyAppliesToOutputs() {
        PolicyContext pre = new PolicyContext(PolicyPhase.PRE_ACTION, NODE, Map.of(), List.of());
        Assert.check(!new SecretsRule().applies(pre), "pre-action has no outputs to scan");
        Assert.check(new SecretsRule().applies(post("x")), "post-action applies");
    }

    private static PolicyContext release(String unit, String integ, String sec, String rel) {
        Node n = Node.approval("RELEASE_APPROVE", HIGH);
        return new PolicyContext(PolicyPhase.PRE_ACTION, n, Map.of(UNIT_TEST_REPORT, unit, INTEGRATION_TEST_REPORT, integ,
                SEC_REPORT, sec, RELEASE_REPORT, rel), List.of());
    }

    public void testReleaseRuleAllowsOnlyGreenVerification() {
        ReleaseGreenRule r = new ReleaseGreenRule("RELEASE_APPROVE", 80);
        String unit = "passed: 10, failed: 0\nLine coverage: 87%";
        String integ = "passed: 3, failed: 0";
        String sec = "high: 0, medium: 1";
        String rel = "Verdict: READY";
        Assert.eq(PolicyOutcome.ALLOW, r.evaluate(release(unit, integ, sec, rel)).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(release("passed: 9, failed: 1\nLine coverage: 87%", integ, sec, rel)).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(release("failed: 0\nLine coverage: 70%", integ, sec, rel)).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(release(unit, "failed: 2", sec, rel)).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(release(unit, integ, "high: 3", rel)).outcome());
        Assert.eq(PolicyOutcome.DENY, r.evaluate(release(unit, integ, sec, "Verdict: NOT READY")).outcome());
    }

    public void testReleaseRuleFailsClosedWhenArtifactsAreMissing() {
        Node n = Node.approval("RELEASE_APPROVE", HIGH);
        PolicyContext ctx = new PolicyContext(PolicyPhase.PRE_ACTION, n, Map.of(), List.of());
        Assert.eq(PolicyOutcome.DENY, new ReleaseGreenRule("RELEASE_APPROVE", 80).evaluate(ctx).outcome());
    }

    private static PolicyRule rule(String id, PolicyOutcome outcome, boolean applies) {
        return new PolicyRule() {
            @Override public String id() { return id; }
            @Override public boolean applies(PolicyContext ctx) { return applies; }
            @Override public PolicyVerdict evaluate(PolicyContext ctx) { return new PolicyVerdict(outcome, id, "test"); }
        };
    }

    public void testEngineReturnsMostSevereVerdictAndLogsOnlyApplicableRules() {
        EventLog log = new EventLog(null);
        PolicyEngine e = new PolicyEngine(List.of(rule("A", PolicyOutcome.ALLOW, true), rule("B", PolicyOutcome.REQUIRE_APPROVAL, true),
                rule("C", PolicyOutcome.DENY, true), rule("D", PolicyOutcome.DENY, false)), log);
        PolicyVerdict v = e.evaluate(post("x"));
        Assert.eq(PolicyOutcome.DENY, v.outcome());
        Assert.eq("C", v.ruleId());
        Assert.eq(3L, log.count("POLICY_EVALUATED")); // D does not apply, so it is not evaluated or logged
    }

    public void testEngineWithNoApplicableRulesAllows() {
        PolicyEngine e = new PolicyEngine(List.of(rule("D", PolicyOutcome.DENY, false)), new EventLog(null));
        Assert.eq(PolicyOutcome.ALLOW, e.evaluate(post("x")).outcome());
    }
}
