package com.agentic.policy;

import com.agentic.gate.GateChecks;
import com.agentic.model.ArtifactType;

import java.util.regex.Pattern;

/**
 * REL-001: a release may only be put in front of a human if verification is green. This is deliberately
 * redundant with the upstream exit gates (defence in depth) and re-reads the artifacts independently.
 */
public final class ReleaseGreenRule implements PolicyRule {
    private static final Pattern FAILED_ZERO = Pattern.compile("failed:\\s*0\\b");
    private static final Pattern HIGH_ZERO = Pattern.compile("high:\\s*0\\b");
    private static final Pattern COVERAGE = Pattern.compile("(?i)coverage:\\s*([0-9.]+)%");

    private final String nodeId;
    private final double minCoverage;

    public ReleaseGreenRule(String nodeId, double minCoverage) {
        this.nodeId = nodeId;
        this.minCoverage = minCoverage;
    }

    @Override public String id() { return "REL-001"; }

    @Override public boolean applies(PolicyContext ctx) {
        return ctx.phase() == PolicyPhase.PRE_ACTION && ctx.node().id().equals(nodeId);
    }

    @Override
    public PolicyVerdict evaluate(PolicyContext ctx) {
        String unit = ctx.inputs().get(ArtifactType.UNIT_TEST_REPORT);
        String integ = ctx.inputs().get(ArtifactType.INTEGRATION_TEST_REPORT);
        String sec = ctx.inputs().get(ArtifactType.SEC_REPORT);
        String rel = ctx.inputs().get(ArtifactType.RELEASE_REPORT);
        if (unit == null || integ == null || sec == null || rel == null) return PolicyVerdict.deny(id(), "verification artifacts missing");
        if (!FAILED_ZERO.matcher(unit).find()) return PolicyVerdict.deny(id(), "unit tests not green");
        if (!FAILED_ZERO.matcher(integ).find()) return PolicyVerdict.deny(id(), "integration tests not green");
        if (!HIGH_ZERO.matcher(sec).find()) return PolicyVerdict.deny(id(), "security scan has high findings");
        Double cov = GateChecks.extract(unit, COVERAGE);
        if (cov == null || cov < minCoverage) return PolicyVerdict.deny(id(), "coverage " + cov + " below " + minCoverage);
        if (!rel.contains("Verdict: READY")) return PolicyVerdict.deny(id(), "release report verdict is not READY");
        return PolicyVerdict.allow(id(), "verification green, coverage " + cov + "%");
    }
}
