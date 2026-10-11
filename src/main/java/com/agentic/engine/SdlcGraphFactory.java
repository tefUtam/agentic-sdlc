package com.agentic.engine;

import com.agentic.dag.Dag;
import com.agentic.dag.EdgeKind;
import com.agentic.dag.Node;
import com.agentic.gate.GateChecks;

import static com.agentic.model.AgentRole.*;
import static com.agentic.model.ArtifactType.*;
import static com.agentic.model.RiskTier.*;

/** Builds the standard SDLC graph (design doc section 4.3). Gates here are the deterministic exit criteria. */
public final class SdlcGraphFactory {
    private SdlcGraphFactory() {}

    public static Dag greenfield() {
        Dag d = new Dag();

        d.addNode(Node.agent("REQ_ANALYZE", REQUIREMENTS, LOW).out(AMBIGUITY_REPORT).paths("docs/requirements/")
                .exit(GateChecks.numberAtMost("ambiguity-at-most-0.6", AMBIGUITY_REPORT, "(?i)ambiguity score:\\s*([0-9.]+)", 0.6)));
        d.addNode(Node.agent("REQ_NORMALIZE", REQUIREMENTS, LOW).in(AMBIGUITY_REPORT).out(REQUIREMENT_SPEC).paths("docs/requirements/")
                .exit(GateChecks.contains(REQUIREMENT_SPEC, "## Requirements"), GateChecks.contains(REQUIREMENT_SPEC, "## Assumptions")));
        d.addNode(Node.approval("REQ_APPROVE", HIGH).in(REQUIREMENT_SPEC));

        d.addNode(Node.agent("DESIGN_ARCH", ARCHITECT, MEDIUM).in(REQUIREMENT_SPEC).out(ARCH_DOC).paths("docs/design/")
                .exit(GateChecks.contains(ARCH_DOC, "## Components")));
        d.addNode(Node.agent("DESIGN_API", ARCHITECT, MEDIUM).in(ARCH_DOC).out(OPENAPI_SPEC).paths("docs/api/")
                .exit(GateChecks.contains(OPENAPI_SPEC, "openapi:")));
        d.addNode(Node.agent("DESIGN_DATA", ARCHITECT, MEDIUM).in(ARCH_DOC).out(DB_SCHEMA).paths("docs/data/")
                .exit(GateChecks.contains(DB_SCHEMA, "CREATE TABLE")));
        d.addNode(Node.sync("DESIGN_SYNC"));
        d.addNode(Node.approval("DESIGN_APPROVE", HIGH).in(ARCH_DOC, OPENAPI_SPEC, DB_SCHEMA));

        d.addNode(Node.agent("IMPL_CORE", IMPLEMENTER, MEDIUM).in(OPENAPI_SPEC, DB_SCHEMA).out(CODE_CORE).paths("src/main/core/")
                .exit(GateChecks.contains(CODE_CORE, "package ")));
        d.addNode(Node.agent("IMPL_ANALYTICS", IMPLEMENTER, MEDIUM).in(OPENAPI_SPEC, DB_SCHEMA).out(CODE_ANALYTICS).paths("src/main/analytics/")
                .exit(GateChecks.contains(CODE_ANALYTICS, "package ")));
        d.addNode(Node.sync("CODE_SYNC"));

        d.addNode(Node.agent("TEST_UNIT", TESTER, LOW).in(CODE_CORE, CODE_ANALYTICS).out(UNIT_TEST_REPORT).paths("tests/unit/")
                .exit(GateChecks.matches("unit-tests-green", UNIT_TEST_REPORT, "failed:\\s*0\\b"),
                        GateChecks.numberAtLeast("coverage-at-least-80", UNIT_TEST_REPORT, "(?i)coverage:\\s*([0-9.]+)%", 80)));
        d.addNode(Node.agent("TEST_INTEGRATION", TESTER, LOW).in(CODE_CORE, CODE_ANALYTICS, OPENAPI_SPEC).out(INTEGRATION_TEST_REPORT).paths("tests/integration/")
                .exit(GateChecks.matches("integration-tests-green", INTEGRATION_TEST_REPORT, "failed:\\s*0\\b")));
        d.addNode(Node.agent("SEC_SCAN", SECURITY, LOW).in(CODE_CORE, CODE_ANALYTICS).out(SEC_REPORT).paths("reports/security/")
                .exit(GateChecks.matches("no-high-findings", SEC_REPORT, "high:\\s*0\\b")));
        d.addNode(Node.agent("DOCS_GEN", DOCS, LOW).in(ARCH_DOC, OPENAPI_SPEC, CODE_CORE).out(DOCS_BUNDLE).paths("docs/")
                .exit(GateChecks.contains(DOCS_BUNDLE, "# ")));
        d.addNode(Node.sync("VERIFY_SYNC"));

        d.addNode(Node.agent("RELEASE_READINESS", RELEASE, MEDIUM).in(UNIT_TEST_REPORT, INTEGRATION_TEST_REPORT, SEC_REPORT, DOCS_BUNDLE)
                .out(RELEASE_REPORT).paths("reports/release/")
                .exit(GateChecks.contains(RELEASE_REPORT, "Verdict: READY")));
        d.addNode(Node.approval("RELEASE_APPROVE", HIGH).in(RELEASE_REPORT, UNIT_TEST_REPORT, INTEGRATION_TEST_REPORT, SEC_REPORT));
        d.addNode(Node.finalNode("DONE"));

        d.edge("REQ_ANALYZE", "REQ_NORMALIZE").edge("REQ_NORMALIZE", "REQ_APPROVE").edge("REQ_APPROVE", "DESIGN_ARCH");
        d.edge("DESIGN_ARCH", "DESIGN_API").edge("DESIGN_ARCH", "DESIGN_DATA");
        d.edge("DESIGN_API", "DESIGN_SYNC", EdgeKind.SYNC).edge("DESIGN_DATA", "DESIGN_SYNC", EdgeKind.SYNC);
        d.edge("DESIGN_SYNC", "DESIGN_APPROVE").edge("DESIGN_APPROVE", "IMPL_CORE").edge("DESIGN_APPROVE", "IMPL_ANALYTICS");
        d.edge("IMPL_CORE", "CODE_SYNC", EdgeKind.SYNC).edge("IMPL_ANALYTICS", "CODE_SYNC", EdgeKind.SYNC);
        d.edge("CODE_SYNC", "TEST_UNIT").edge("CODE_SYNC", "TEST_INTEGRATION").edge("CODE_SYNC", "DOCS_GEN");
        d.edge("TEST_UNIT", "SEC_SCAN"); // ordering-only dependency: the scan runs after unit tests pass
        d.edge("SEC_SCAN", "VERIFY_SYNC", EdgeKind.SYNC).edge("TEST_INTEGRATION", "VERIFY_SYNC", EdgeKind.SYNC)
                .edge("DOCS_GEN", "VERIFY_SYNC", EdgeKind.SYNC);
        d.edge("VERIFY_SYNC", "RELEASE_READINESS").edge("RELEASE_READINESS", "RELEASE_APPROVE").edge("RELEASE_APPROVE", "DONE");
        return d;
    }
}
