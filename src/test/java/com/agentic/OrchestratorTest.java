package com.agentic;

import com.agentic.agent.LlmClient;
import com.agentic.agent.LlmResponse;
import com.agentic.agent.StubLlmClient;
import com.agentic.approval.ApprovalServer;
import com.agentic.approval.ApprovalStatus;
import com.agentic.approval.AutoApprover;
import com.agentic.dag.Dag;
import com.agentic.dag.Node;
import com.agentic.dag.NodeState;
import com.agentic.engine.Orchestrator;
import com.agentic.engine.Orchestrator.RunResult;
import com.agentic.engine.Orchestrator.RunStatus;
import com.agentic.engine.SdlcGraphFactory;
import com.agentic.model.Artifact;
import com.agentic.model.ArtifactStatus;
import com.agentic.model.ArtifactType;
import com.agentic.model.Decision;
import com.agentic.policy.PolicyContext;
import com.agentic.policy.PolicyEngine;
import com.agentic.policy.PolicyPhase;
import com.agentic.policy.PolicyRule;
import com.agentic.policy.PolicyVerdict;
import com.agentic.policy.ReleaseGreenRule;
import com.agentic.policy.SecretsRule;
import com.agentic.store.Event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.agentic.model.AgentRole.*;
import static com.agentic.model.ArtifactType.*;
import static com.agentic.model.RiskTier.*;

/** End-to-end tests of the run loop using the deterministic stub model. */
public class OrchestratorTest {
    private static final String REQ = "Build a URL shortener service with APIs to create short links and redirect, click analytics, "
            + "and reliability features such as rate limiting and health checks.";

    // ---- helpers

    private static Orchestrator newRun(String requirement, LlmClient llm, List<PolicyRule> rules, Orchestrator.Config cfg) throws Exception {
        return Orchestrator.create(Files.createTempDirectory("agentic-run"), requirement, SdlcGraphFactory.greenfield(), llm, cfg, rules);
    }

    private static Orchestrator newRun(LlmClient llm) throws Exception {
        return newRun(REQ, llm, PolicyEngine.defaultRules(), new Orchestrator.Config(4, Duration.ofSeconds(30)));
    }

    private static LlmClient override(ArtifactType target, String content, long latency) {
        StubLlmClient stub = new StubLlmClient(latency);
        return r -> r.target() == target ? new LlmResponse(content, "") : stub.complete(r);
    }

    private static RunResult runApproving(Orchestrator o) {
        AutoApprover a = AutoApprover.start(o.approvals(), req -> ApprovalStatus.APPROVED, "test-approver");
        try {
            return o.run();
        } finally {
            a.stop();
        }
    }

    private static long seq(Orchestrator o, String type, String nodeId, String payloadContains) {
        for (Event e : o.eventLog().events()) {
            if (e.type().equals(type) && nodeId.equals(e.nodeId()) && e.payloadJson().contains(payloadContains)) return e.seq();
        }
        return -1;
    }

    private static boolean agentWasCalled(Orchestrator o, String nodeId) {
        return o.eventLog().events().stream().anyMatch(e -> e.type().equals("AGENT_CALLED") && nodeId.equals(e.nodeId()));
    }

    private static NodeState state(Orchestrator o, String id) { return o.dag().node(id).state(); }

    // ---- greenfield happy path

    public void testGreenfieldRunsToCompletionWithAllGovernanceRecorded() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(30));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.DONE, r.status());
        o.dag().nodes().forEach(n -> Assert.eq(NodeState.SUCCEEDED, n.state()));
        Assert.eq(3, r.approvalsRequested());
        Assert.eq(12, o.store().all().size());
        Assert.check(o.store().all().stream().allMatch(a -> a.status() == ArtifactStatus.VALID), "all artifacts VALID");
        Assert.check(o.eventLog().verifyChain(), "audit chain must verify");
    }

    public void testDecisionLineageLeadsBackToTheRequirement() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(0));
        runApproving(o);
        Decision redirect = o.context().decisions().stream().filter(d -> d.rationale().contains("302")).findFirst().orElseThrow();
        Assert.check(redirect.artifactRefs().contains("ARCH_DOC:v1"), "decision must reference the artifact it shaped");
        List<String> lineage = o.store().lineage("RELEASE_REPORT:v1").stream().map(Artifact::id).toList();
        Assert.check(lineage.contains("REQUIREMENT_SPEC:v1"), "release report must trace to the requirement: " + lineage);
        Assert.check(lineage.contains("AMBIGUITY_REPORT:v1"), "and to the ambiguity analysis: " + lineage);
        Assert.check(o.context().decisions().stream().anyMatch(d -> d.kind().equals("APPROVAL_APPROVED") && d.actor().equals("test-approver")),
                "human approvals are recorded as decisions with the approver identity");
    }

    public void testIndependentBranchesRunInParallel() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(60));
        RunResult r = runApproving(o);
        Assert.check(r.peakParallelism() >= 3, "test/docs fan-out should reach 3 concurrent agents, was " + r.peakParallelism());
        long apiRun = seq(o, "NODE_STATE_CHANGED", "DESIGN_API", "\"to\":\"RUNNING\"");
        long dataRun = seq(o, "NODE_STATE_CHANGED", "DESIGN_DATA", "\"to\":\"RUNNING\"");
        long apiDone = seq(o, "NODE_STATE_CHANGED", "DESIGN_API", "\"to\":\"SUCCEEDED\"");
        long dataDone = seq(o, "NODE_STATE_CHANGED", "DESIGN_DATA", "\"to\":\"SUCCEEDED\"");
        Assert.check(Math.max(apiRun, dataRun) < Math.min(apiDone, dataDone), "both design branches must be in flight at the same time");
    }

    public void testSyncBarrierOpensOnlyAfterEveryBranchAndGatesTheNextStage() throws Exception {
        Orchestrator o = newRun(override(OPENAPI_SPEC, "openapi: 3.0.3\npaths: {}", 0)); // fast API branch
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.DONE, r.status());
        long apiDone = seq(o, "NODE_STATE_CHANGED", "DESIGN_API", "\"to\":\"SUCCEEDED\"");
        long dataDone = seq(o, "NODE_STATE_CHANGED", "DESIGN_DATA", "\"to\":\"SUCCEEDED\"");
        long sync = seq(o, "NODE_STATE_CHANGED", "DESIGN_SYNC", "\"to\":\"SUCCEEDED\"");
        long approveAsked = seq(o, "NODE_STATE_CHANGED", "DESIGN_APPROVE", "\"to\":\"AWAITING_APPROVAL\"");
        Assert.check(sync > apiDone && sync > dataDone, "barrier must follow both branches");
        Assert.check(approveAsked > sync, "design sign-off must be requested only after the barrier");
    }

    public void testConcurrencyBoundIsRespected() throws Exception {
        Orchestrator o = newRun(REQ, new StubLlmClient(20), PolicyEngine.defaultRules(), new Orchestrator.Config(1, Duration.ofSeconds(30)));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.DONE, r.status());
        Assert.eq(1, r.peakParallelism());
    }

    public void testRunStateIsPersistedAfterTheRun() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(0));
        runApproving(o);
        String state = Files.readString(o.runDir().resolve("state.json"));
        Assert.contains(state, "\"status\":\"DONE\"");
        Assert.contains(state, "\"RELEASE_APPROVE\":{\"kind\":\"APPROVAL\",\"state\":\"SUCCEEDED\"");
        Assert.eq(o.eventLog().events().size(), Files.readAllLines(o.runDir().resolve("events.jsonl")).size());
    }

    // ---- safe-stop triggers

    public void testAmbiguousRequirementStopsAtTheGateAndKeepsTheRejectedEvidence() throws Exception {
        Orchestrator o = newRun("Make links smarter and more secure", new StubLlmClient(0), PolicyEngine.defaultRules(),
                new Orchestrator.Config(4, Duration.ofSeconds(30)));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "ambiguity-at-most-0.6");
        Assert.eq(NodeState.FAILED, state(o, "REQ_ANALYZE"));
        Assert.eq(NodeState.SAFE_STOPPED, state(o, "REQ_NORMALIZE"));
        Assert.check(!agentWasCalled(o, "DESIGN_ARCH"), "nothing downstream may run");
        Assert.eq(ArtifactStatus.REJECTED, o.store().get("AMBIGUITY_REPORT:v1").orElseThrow().status());
        Assert.check(Files.exists(Path.of(o.store().get("AMBIGUITY_REPORT:v1").orElseThrow().path())), "rejected artifacts stay on disk for audit");
        Assert.check(o.store().latestValid(AMBIGUITY_REPORT).isEmpty(), "but they are never resolvable as inputs");
        Assert.eq(0, r.approvalsRequested());
    }

    public void testFailingExitGateStopsTheRunBeforeDependentWork() throws Exception {
        Orchestrator o = newRun(override(UNIT_TEST_REPORT, "Unit tests: passed: 40, failed: 0\nLine coverage: 50%", 20));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "coverage-at-least-80");
        Assert.eq(NodeState.FAILED, state(o, "TEST_UNIT"));
        Assert.eq(NodeState.SAFE_STOPPED, state(o, "SEC_SCAN"));      // ordered after TEST_UNIT: never started
        Assert.eq(NodeState.SAFE_STOPPED, state(o, "RELEASE_READINESS"));
        Assert.eq(ArtifactStatus.REJECTED, o.store().get("UNIT_TEST_REPORT:v1").orElseThrow().status());
        Assert.check(!agentWasCalled(o, "SEC_SCAN"), "SEC_SCAN must not run");
        Assert.check(o.eventLog().verifyChain(), "chain still verifies after a stop");
    }

    public void testSecretsInAgentOutputAreDeniedAndNeverWrittenToDisk() throws Exception {
        String leaked = "CREATE TABLE t (id INT); -- aws AKIAABCDEFGHIJKLMNOP";
        Orchestrator o = newRun(override(DB_SCHEMA, leaked, 0));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "SEC-001");
        Assert.eq(NodeState.FAILED, state(o, "DESIGN_DATA"));
        Assert.check(o.store().all().stream().noneMatch(a -> a.type() == DB_SCHEMA), "denied output must not be committed");
        try (Stream<Path> files = Files.walk(o.runDir())) {
            for (Path p : files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("events.jsonl")).toList()) {
                Assert.check(!Files.readString(p).contains("AKIAABCDEFGHIJKLMNOP"), "secret leaked into " + p);
            }
        }
    }

    public void testAgentExceptionIsContainedAndStopsTheRun() throws Exception {
        StubLlmClient stub = new StubLlmClient(0);
        LlmClient boom = r -> {
            if (r.target() == CODE_CORE) throw new IllegalStateException("model backend unavailable");
            return stub.complete(r);
        };
        Orchestrator o = newRun(boom);
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "model backend unavailable");
        Assert.eq(NodeState.FAILED, state(o, "IMPL_CORE"));
        Assert.check(!agentWasCalled(o, "TEST_UNIT"), "downstream of a failed node never runs");
    }

    public void testReleaseRuleBlocksNonGreenReleaseBeforeAHumanIsAsked() throws Exception {
        // Exit gates pass (coverage 87 >= 80) but the independent release rule demands 95: defence in depth.
        List<PolicyRule> rules = List.of(new SecretsRule(), new ReleaseGreenRule("RELEASE_APPROVE", 95.0));
        Orchestrator o = newRun(REQ, new StubLlmClient(0), rules, new Orchestrator.Config(4, Duration.ofSeconds(30)));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "REL-001");
        Assert.eq(NodeState.BLOCKED, state(o, "RELEASE_APPROVE"));
        Assert.eq(2, r.approvalsRequested()); // requirement and design sign-offs only: no human is asked to approve a red release
    }

    public void testInvalidGraphIsRefusedBeforeAnyAgentRuns() throws Exception {
        Dag cyclic = new Dag().addNode(Node.agent("A", TESTER, LOW)).addNode(Node.agent("B", TESTER, LOW))
                .addNode(Node.finalNode("END")).edge("A", "B").edge("B", "A").edge("B", "END");
        Orchestrator o = Orchestrator.create(Files.createTempDirectory("agentic-run"), REQ, cyclic, new StubLlmClient(0),
                new Orchestrator.Config(2, Duration.ofSeconds(5)));
        RunResult r = o.run();
        Assert.eq(RunStatus.ABORTED, r.status());
        Assert.contains(r.reason(), "cycle");
        Assert.eq(0L, o.eventLog().count("AGENT_CALLED"));
    }

    public void testWaitingForeverOnAHumanIsBoundedByTheRunTimeout() throws Exception {
        Orchestrator o = newRun(REQ, new StubLlmClient(0), PolicyEngine.defaultRules(), new Orchestrator.Config(4, Duration.ofMillis(400)));
        RunResult r = o.run(); // nobody approves
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.contains(r.reason(), "timeout");
        Assert.eq(NodeState.SAFE_STOPPED, state(o, "REQ_APPROVE"));
        Assert.eq(0, o.approvals().pending().size()); // open approvals are cancelled on stop
    }

    public void testAgentThatFinishesAfterASafeStopHasItsResultDiscarded() throws Exception {
        // Two parallel design branches; DATA fails fast, API is slow and finishes after the stop.
        StubLlmClient stub = new StubLlmClient(0);
        LlmClient mixed = r -> {
            if (r.target() == DB_SCHEMA) return new LlmResponse("no schema here", "");
            if (r.target() == OPENAPI_SPEC) Thread.sleep(300);
            return stub.complete(r);
        };
        Orchestrator o = newRun(mixed);
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.SAFE_STOPPED, r.status());
        Assert.eq(NodeState.FAILED, state(o, "DESIGN_DATA"));
        Assert.eq(NodeState.SAFE_STOPPED, state(o, "DESIGN_API"));
        Assert.eq(1L, o.eventLog().count("AGENT_RESULT_DISCARDED"));
        Assert.check(o.store().all().stream().noneMatch(a -> a.type() == OPENAPI_SPEC), "late result must not be committed");
    }

    // ---- human approvals

    public void testRejectedSignOffAbortsTheRun() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(0));
        AutoApprover a = AutoApprover.start(o.approvals(),
                req -> req.nodeId().equals("DESIGN_APPROVE") ? ApprovalStatus.REJECTED : ApprovalStatus.APPROVED, "reviewer");
        RunResult r;
        try {
            r = o.run();
        } finally {
            a.stop();
        }
        Assert.eq(RunStatus.ABORTED, r.status());
        Assert.contains(r.reason(), "rejected");
        Assert.eq(NodeState.ABORTED, state(o, "DESIGN_APPROVE"));
        Assert.check(!agentWasCalled(o, "IMPL_CORE"), "nothing is implemented without design sign-off");
        Assert.eq(NodeState.SUCCEEDED, state(o, "REQ_APPROVE")); // earlier approved work is preserved
    }

    private static PolicyRule approvalForImplCore() {
        return new PolicyRule() {
            @Override public String id() { return "TEST-001"; }
            @Override public boolean applies(PolicyContext c) { return c.phase() == PolicyPhase.PRE_ACTION && c.node().id().equals("IMPL_CORE"); }
            @Override public PolicyVerdict evaluate(PolicyContext c) { return PolicyVerdict.requireApproval(id(), "touches core code"); }
        };
    }

    public void testPolicyCanGateAnAgentNodeBehindHumanApproval() throws Exception {
        List<PolicyRule> rules = new ArrayList<>(PolicyEngine.defaultRules());
        rules.add(approvalForImplCore());
        Orchestrator o = newRun(REQ, new StubLlmClient(10), rules, new Orchestrator.Config(4, Duration.ofSeconds(30)));
        RunResult r = runApproving(o);
        Assert.eq(RunStatus.DONE, r.status());
        Assert.eq(4, r.approvalsRequested());
        Assert.eq(1, o.dag().node("IMPL_CORE").attempt());
        long asked = seq(o, "APPROVAL_REQUESTED", "IMPL_CORE", "TEST-001");
        long ran = seq(o, "AGENT_CALLED", "IMPL_CORE", "attempt");
        Assert.check(asked > 0 && ran > asked, "the agent must not run until the human has approved");
    }

    public void testPolicyApprovalRejectionMeansTheAgentNeverRuns() throws Exception {
        List<PolicyRule> rules = new ArrayList<>(PolicyEngine.defaultRules());
        rules.add(approvalForImplCore());
        Orchestrator o = newRun(REQ, new StubLlmClient(0), rules, new Orchestrator.Config(4, Duration.ofSeconds(30)));
        AutoApprover a = AutoApprover.start(o.approvals(),
                req -> req.nodeId().equals("IMPL_CORE") ? ApprovalStatus.REJECTED : ApprovalStatus.APPROVED, "reviewer");
        RunResult r;
        try {
            r = o.run();
        } finally {
            a.stop();
        }
        Assert.eq(RunStatus.ABORTED, r.status());
        Assert.check(!agentWasCalled(o, "IMPL_CORE"), "rejected: the agent must never be invoked");
        Assert.eq(NodeState.ABORTED, state(o, "IMPL_CORE"));
    }

    public void testApprovalsCanBeGivenOverHttp() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(0));
        ApprovalServer server = new ApprovalServer(o.approvals(), o::statusSnapshot, 0);
        server.start();
        AtomicBoolean stop = new AtomicBoolean();
        Thread human = new Thread(() -> {
            HttpClient c = HttpClient.newHttpClient();
            Pattern id = Pattern.compile("\"id\":\"(APR-\\d+)\"");
            while (!stop.get()) {
                try {
                    String base = "http://127.0.0.1:" + server.port();
                    String body = c.send(HttpRequest.newBuilder(URI.create(base + "/approvals")).build(), HttpResponse.BodyHandlers.ofString()).body();
                    Matcher m = id.matcher(body);
                    while (m.find()) {
                        c.send(HttpRequest.newBuilder(URI.create(base + "/approvals/" + m.group(1) + "/decision"))
                                .POST(HttpRequest.BodyPublishers.ofString("decision=APPROVE&approver=http-tester&rationale=ok")).build(),
                                HttpResponse.BodyHandlers.ofString());
                    }
                    Thread.sleep(25);
                } catch (Exception e) {
                    return;
                }
            }
        });
        human.setDaemon(true);
        human.start();
        RunResult r;
        try {
            r = o.run();
        } finally {
            stop.set(true);
            server.stop();
        }
        Assert.eq(RunStatus.DONE, r.status());
        long byHuman = o.eventLog().events().stream().filter(e -> e.type().equals("APPROVAL_DECIDED") && "http-tester".equals(e.actor())).count();
        Assert.eq(3L, byHuman);
    }

    public void testStatusSnapshotReportsNodeStates() throws Exception {
        Orchestrator o = newRun(new StubLlmClient(0));
        runApproving(o);
        java.util.Map<String, Object> s = o.statusSnapshot();
        Assert.eq(RunStatus.DONE, s.get("status"));
        Assert.eq(0, s.get("pendingApprovals"));
    }

}
