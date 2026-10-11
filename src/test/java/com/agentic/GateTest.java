package com.agentic;

import com.agentic.gate.Gate;
import com.agentic.gate.GateChecks;
import com.agentic.gate.GateContext;
import com.agentic.gate.GateReport;
import com.agentic.gate.GateResult;
import com.agentic.gate.NamedCheck;
import com.agentic.model.Artifact;
import com.agentic.model.ArtifactStatus;
import com.agentic.model.ArtifactType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.agentic.model.ArtifactType.*;

public class GateTest {

    private static GateContext out(ArtifactType expected, String content) {
        return new GateContext("N", List.of(), List.of(), List.of(expected), Map.of(expected, content));
    }

    private static Artifact art(ArtifactType t, ArtifactStatus s) {
        return new Artifact(t + ":v1", t, 1, "h", s, "N", 1, List.of(), Instant.now(), "p");
    }

    public void testNumericThresholds() {
        String report = "Line coverage: 87%";
        GateContext ctx = out(UNIT_TEST_REPORT, report);
        String re = "(?i)coverage:\\s*([0-9.]+)%";
        Assert.check(GateChecks.numberAtLeast("c", UNIT_TEST_REPORT, re, 80).check().evaluate(ctx).passed(), "87 >= 80");
        Assert.check(!GateChecks.numberAtLeast("c", UNIT_TEST_REPORT, re, 90).check().evaluate(ctx).passed(), "87 < 90");
        Assert.check(GateChecks.numberAtMost("c", UNIT_TEST_REPORT, re, 90).check().evaluate(ctx).passed(), "87 <= 90");
        GateResult missing = GateChecks.numberAtLeast("c", UNIT_TEST_REPORT, re, 1).check().evaluate(out(UNIT_TEST_REPORT, "no numbers here"));
        Assert.check(!missing.passed(), "a missing value must fail, never pass silently");
    }

    public void testContainsAndMatches() {
        GateContext ctx = out(OPENAPI_SPEC, "openapi: 3.0.3\npaths:");
        Assert.check(GateChecks.contains(OPENAPI_SPEC, "openapi:").check().evaluate(ctx).passed(), "contains");
        Assert.check(!GateChecks.contains(OPENAPI_SPEC, "swagger").check().evaluate(ctx).passed(), "not contains");
        Assert.check(GateChecks.matches("m", OPENAPI_SPEC, "3\\.0\\.\\d").check().evaluate(ctx).passed(), "regex match");
    }

    public void testOutputContract() {
        NamedCheck c = GateChecks.outputsMatchContract();
        Assert.check(c.check().evaluate(out(ARCH_DOC, "text")).passed(), "exact match passes");
        Assert.check(!c.check().evaluate(out(ARCH_DOC, "  ")).passed(), "blank fails");
        GateContext extra = new GateContext("N", List.of(), List.of(), List.of(ARCH_DOC), Map.of(ARCH_DOC, "a", DB_SCHEMA, "b"));
        Assert.check(!c.check().evaluate(extra).passed(), "unexpected extra output fails");
        GateContext missing = new GateContext("N", List.of(), List.of(), List.of(ARCH_DOC, DB_SCHEMA), Map.of(ARCH_DOC, "a"));
        Assert.check(!c.check().evaluate(missing).passed(), "missing output fails");
    }

    public void testEntryGateRequiresValidInputs() {
        NamedCheck c = GateChecks.inputsValid();
        GateContext ok = new GateContext("N", List.of(ARCH_DOC), List.of(art(ARCH_DOC, ArtifactStatus.VALID)), List.of(), Map.of());
        GateContext draft = new GateContext("N", List.of(ARCH_DOC), List.of(art(ARCH_DOC, ArtifactStatus.DRAFT)), List.of(), Map.of());
        GateContext none = new GateContext("N", List.of(ARCH_DOC), List.of(), List.of(), Map.of());
        Assert.check(c.check().evaluate(ok).passed(), "valid input passes");
        Assert.check(!c.check().evaluate(draft).passed(), "DRAFT input fails");
        Assert.check(!c.check().evaluate(none).passed(), "missing input fails");
    }

    public void testGateReportsEveryFailureAndSurvivesThrowingChecks() {
        List<NamedCheck> checks = List.of(
                new NamedCheck("first", c -> GateResult.fail("nope-1")),
                new NamedCheck("boom", c -> { throw new IllegalStateException("kaboom"); }),
                new NamedCheck("fine", c -> GateResult.ok("ok")));
        GateReport r = Gate.evaluate(checks, out(ARCH_DOC, "x"));
        Assert.check(!r.passed(), "overall fail");
        Assert.eq(3, r.results().size()); // no short-circuit: the audit trail sees everything
        Assert.contains(r.failureSummary(), "nope-1");
        Assert.contains(r.failureSummary(), "kaboom");
    }
}
