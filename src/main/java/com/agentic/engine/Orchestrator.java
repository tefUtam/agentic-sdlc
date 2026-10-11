package com.agentic.engine;

import com.agentic.agent.Agent;
import com.agentic.agent.AgentDecision;
import com.agentic.agent.AgentRegistry;
import com.agentic.agent.AgentResult;
import com.agentic.agent.LlmClient;
import com.agentic.agent.TaskSpec;
import com.agentic.approval.ApprovalManager;
import com.agentic.approval.ApprovalStatus;
import com.agentic.approval.ApprovalView;
import com.agentic.dag.Dag;
import com.agentic.dag.DagValidationException;
import com.agentic.dag.Node;
import com.agentic.dag.NodeKind;
import com.agentic.dag.NodeState;
import com.agentic.dag.Scheduler;
import com.agentic.dag.StateMachine;
import com.agentic.gate.Gate;
import com.agentic.gate.GateChecks;
import com.agentic.gate.GateContext;
import com.agentic.gate.GateReport;
import com.agentic.gate.NamedCheck;
import com.agentic.model.Artifact;
import com.agentic.model.ArtifactDraft;
import com.agentic.model.ArtifactStatus;
import com.agentic.model.ArtifactType;
import com.agentic.policy.PolicyContext;
import com.agentic.policy.PolicyEngine;
import com.agentic.policy.PolicyOutcome;
import com.agentic.policy.PolicyPhase;
import com.agentic.policy.PolicyRule;
import com.agentic.policy.PolicyVerdict;
import com.agentic.store.ArtifactStore;
import com.agentic.store.EventLog;
import com.agentic.store.Hashing;
import com.agentic.store.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Stateful, graph-driven run loop.
 *
 * <p>Concurrency model: ONE coordinator thread (the caller of {@link #run()}) owns every state transition,
 * gate evaluation, policy check and artifact commit. Agent calls run on a bounded worker pool and report back
 * through an inbox queue; human approvals arrive on the same inbox. This keeps the audit trail strictly ordered
 * and removes data races without locks around node state.
 *
 * <p>Failure handling in this slice is fail-safe: any gate failure, policy denial, agent error, entry-gate failure,
 * deadlock or timeout stops the run (safe-stop) with the cause recorded. Bounded retries, fallback and rollback
 * are the next build step.
 */
public final class Orchestrator {

    public record Config(int maxParallel, Duration runTimeout) {
        public static Config defaults() { return new Config(4, Duration.ofMinutes(10)); }
    }

    public enum RunStatus { ACTIVE, DONE, SAFE_STOPPED, ABORTED }

    public record RunResult(String runId, RunStatus status, String reason, long latencyMs, long agentBusyMs,
                            long humanWaitMs, int peakParallelism, int approvalsRequested,
                            Map<String, NodeState> nodeStates) {}

    private sealed interface Signal permits AgentDone, ApprovalDone {}

    private record AgentDone(String nodeId, int attempt, AgentResult result, Throwable error, long millis) implements Signal {}

    private record ApprovalDone(ApprovalView view) implements Signal {}

    private final String runId;
    private final Dag dag;
    private final Config cfg;
    private final AgentRegistry agents;
    private final PolicyEngine policy;
    private final ApprovalManager approvals;
    private final ArtifactStore store;
    private final EventLog log;
    private final Path runDir;
    private final RunContext context;
    private final BlockingQueue<Signal> inbox = new LinkedBlockingQueue<>();
    private final ExecutorService executor;

    private volatile RunStatus status = RunStatus.ACTIVE;
    private volatile String stopReason;
    private int inFlight;
    private int peak;
    private int approvalsRequested;
    private long humanWaitMs;
    private Instant startedAt;

    private Orchestrator(String runId, Dag dag, String requirement, Config cfg, AgentRegistry agents, PolicyEngine policy,
                         ApprovalManager approvals, ArtifactStore store, EventLog log, Path runDir) {
        this.runId = runId;
        this.dag = dag;
        this.cfg = cfg;
        this.agents = agents;
        this.policy = policy;
        this.approvals = approvals;
        this.store = store;
        this.log = log;
        this.runDir = runDir;
        this.context = new RunContext(runId, requirement);
        AtomicInteger workerSeq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(cfg.maxParallel(), r -> {
            Thread t = new Thread(r, "agent-worker-" + workerSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public static Orchestrator create(Path baseDir, String requirement, Dag dag, LlmClient llm, Config cfg, List<PolicyRule> rules) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        String runId = "run-" + stamp + "-" + UUID.randomUUID().toString().substring(0, 4);
        Path runDir = baseDir.resolve(runId);
        EventLog log = new EventLog(runDir.resolve("events.jsonl"));
        ArtifactStore store = new ArtifactStore(runDir.resolve("artifacts"));
        return new Orchestrator(runId, dag, requirement, cfg, AgentRegistry.withLlm(llm), new PolicyEngine(rules, log),
                new ApprovalManager(log), store, log, runDir);
    }

    public static Orchestrator create(Path baseDir, String requirement, Dag dag, LlmClient llm, Config cfg) {
        return create(baseDir, requirement, dag, llm, cfg, PolicyEngine.defaultRules());
    }

    public String runId() { return runId; }
    public Dag dag() { return dag; }
    public ApprovalManager approvals() { return approvals; }
    public ArtifactStore store() { return store; }
    public EventLog eventLog() { return log; }
    public RunContext context() { return context; }
    public Path runDir() { return runDir; }
    public RunStatus status() { return status; }
    public String stopReason() { return stopReason; }

    /** Safe to call from any thread (HTTP status endpoint). */
    public Map<String, Object> statusSnapshot() {
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (Node n : dag.nodes()) nodes.put(n.id(), n.state());
        return Json.obj("runId", runId, "status", status, "reason", stopReason, "nodes", nodes,
                "pendingApprovals", approvals.pending().size());
    }

    // ------------------------------------------------------------------ run loop

    public RunResult run() {
        startedAt = Instant.now();
        log.append("RUN_STARTED", null, "orchestrator", Json.obj("runId", runId, "requirement", context.requirement(),
                "nodes", dag.nodes().size(), "edges", dag.edges().size(), "maxParallel", cfg.maxParallel()));
        List<String> order;
        try {
            dag.validate();
            order = dag.topologicalOrder();
            log.append("GRAPH_VALIDATED", null, "orchestrator", Json.obj("order", order));
        } catch (DagValidationException e) {
            status = RunStatus.ABORTED;
            stopReason = e.getMessage();
            log.append("RUN_ABORTED", null, "orchestrator", Json.obj("reason", stopReason));
            return finish();
        }

        approvals.setListener(v -> inbox.offer(new ApprovalDone(v)));
        snapshot();
        Instant deadline = startedAt.plus(cfg.runTimeout());
        try {
            while (status == RunStatus.ACTIVE) {
                dispatchReady(order);
                if (status != RunStatus.ACTIVE) break;
                if (allSucceeded()) {
                    status = RunStatus.DONE;
                    break;
                }
                if (inFlight == 0 && awaitingApproval() == 0) {
                    stop(RunStatus.SAFE_STOPPED, "deadlock: no runnable nodes but the run is incomplete");
                    break;
                }
                if (Instant.now().isAfter(deadline)) {
                    stop(RunStatus.SAFE_STOPPED, "run timeout exceeded (" + cfg.runTimeout() + ")");
                    break;
                }
                Signal s = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (s != null) handle(s);
            }
            // Let in-flight agent calls finish; after a stop their results are discarded.
            Instant drainUntil = Instant.now().plusSeconds(30);
            while (inFlight > 0 && Instant.now().isBefore(drainUntil)) {
                Signal s = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (s != null) handle(s);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(RunStatus.SAFE_STOPPED, "interrupted");
        } finally {
            executor.shutdown();
            approvals.setListener(null);
        }
        return finish();
    }

    private void dispatchReady(List<String> order) {
        boolean progress;
        do {
            progress = false;
            for (String id : order) {
                if (status != RunStatus.ACTIVE) return;
                Node n = dag.node(id);
                if (!Scheduler.isReady(dag, n)) continue;
                switch (n.kind()) {
                    case SYNC, FINAL -> {
                        transition(n, NodeState.READY, "dependencies satisfied");
                        n.setEndedAt(Instant.now());
                        transition(n, NodeState.SUCCEEDED, n.kind() == NodeKind.FINAL
                                ? "run complete" : "barrier released: all branches succeeded");
                        progress = true;
                    }
                    case APPROVAL -> startApproval(n);
                    case AGENT -> {
                        if (inFlight >= cfg.maxParallel()) continue; // concurrency bound; stays PENDING
                        transition(n, NodeState.READY, "dependencies satisfied");
                        prepareAndRun(n);
                    }
                }
            }
        } while (progress);
    }

    private void handle(Signal s) {
        switch (s) {
            case AgentDone d -> onAgentDone(d);
            case ApprovalDone a -> onApprovalDone(a.view());
        }
    }

    // ------------------------------------------------------------------ starting work

    private void startApproval(Node n) {
        transition(n, NodeState.READY, "dependencies satisfied");
        List<Artifact> inputs = resolveInputs(n);
        if (!entryGatePasses(n, inputs)) return;
        PolicyVerdict v = policy.evaluate(new PolicyContext(PolicyPhase.PRE_ACTION, n, contentOf(inputs), List.of()));
        if (v.outcome() == PolicyOutcome.DENY) {
            block(n, "policy " + v.ruleId() + " denied: " + v.detail());
            return;
        }
        transition(n, NodeState.AWAITING_APPROVAL, "human sign-off required");
        requestApproval(n, inputs, List.of("HIGH-impact checkpoint at " + n.id()));
    }

    private void prepareAndRun(Node n) {
        List<Artifact> inputs = resolveInputs(n);
        if (!entryGatePasses(n, inputs)) return;
        if (!n.policyApproved()) {
            PolicyVerdict v = policy.evaluate(new PolicyContext(PolicyPhase.PRE_ACTION, n, contentOf(inputs), List.of()));
            if (v.outcome() == PolicyOutcome.DENY) {
                block(n, "policy " + v.ruleId() + " denied: " + v.detail());
                return;
            }
            if (v.outcome() == PolicyOutcome.REQUIRE_APPROVAL) {
                transition(n, NodeState.AWAITING_APPROVAL, "policy " + v.ruleId() + " requires human approval");
                requestApproval(n, inputs, List.of(v.ruleId() + ": " + v.detail()));
                return;
            }
        }
        dispatch(n, inputs);
    }

    private void dispatch(Node n, List<Artifact> inputs) {
        n.setConsumedInputs(inputs);
        n.setInputHash(Hashing.sha256(inputs.stream().map(Artifact::contentHash).collect(Collectors.joining("|"))));
        final int attempt = n.incrementAttempt();
        transition(n, NodeState.RUNNING, "attempt " + attempt);
        n.setStartedAt(Instant.now());
        TaskSpec task = new TaskSpec(runId, n.id(), n.role(), attempt, context.scopedView(n.role()), inputs,
                contentOf(inputs), n.outputs(), null);
        Agent agent = agents.forRole(n.role());
        inFlight++;
        peak = Math.max(peak, inFlight);
        log.append("AGENT_CALLED", n.id(), n.role().name(), Json.obj("attempt", attempt,
                "inputs", inputs.stream().map(Artifact::id).toList(), "inputHash", n.inputHash()));
        final String nodeId = n.id();
        executor.submit(() -> {
            long t0 = System.nanoTime();
            AgentResult result = null;
            Throwable error = null;
            try {
                result = agent.execute(task);
            } catch (Throwable t) {
                error = t;
            }
            inbox.offer(new AgentDone(nodeId, attempt, result, error, (System.nanoTime() - t0) / 1_000_000));
        });
    }

    private void requestApproval(Node n, List<Artifact> inputs, List<String> reasons) {
        String hash = Hashing.sha256(inputs.stream().map(Artifact::contentHash).collect(Collectors.joining("|")));
        List<String> refs = inputs.stream().map(Artifact::id).toList();
        String summary = n.kind() == NodeKind.APPROVAL
                ? "Sign-off required at " + n.id() + " for " + refs
                : "Policy requires approval before running " + n.id();
        approvals.request(runId, n.id(), hash, summary, refs, reasons);
        approvalsRequested++;
    }

    // ------------------------------------------------------------------ handling results

    private void onAgentDone(AgentDone d) {
        inFlight--;
        Node n = dag.node(d.nodeId());
        n.addRunMillis(d.millis());
        n.setEndedAt(Instant.now());

        if (status != RunStatus.ACTIVE) {
            log.append("AGENT_RESULT_DISCARDED", n.id(), "orchestrator", Json.obj("reason", "run stopped while agent was in flight"));
            transition(n, NodeState.SAFE_STOPPED, "run stopped while in flight");
            return;
        }
        if (d.error() != null) {
            fail(n, "agent error: " + d.error());
            return;
        }
        List<ArtifactDraft> drafts = d.result().outputs();
        if (drafts.stream().map(ArtifactDraft::type).distinct().count() != drafts.size()) {
            fail(n, "agent returned duplicate output types");
            return;
        }

        // Policy runs BEFORE anything is persisted, so a denied output (e.g. a secret) never touches disk.
        PolicyVerdict pv = policy.evaluate(new PolicyContext(PolicyPhase.POST_ACTION, n, Map.of(), drafts));
        if (pv.outcome() != PolicyOutcome.ALLOW) {
            fail(n, "policy " + pv.ruleId() + " " + pv.outcome() + ": " + pv.detail());
            return;
        }

        List<Artifact> inputs = n.consumedInputs();
        List<String> parentIds = inputs.stream().map(Artifact::id).toList();
        List<Artifact> committed = new ArrayList<>();
        for (ArtifactDraft dr : drafts) {
            Artifact a = store.commit(dr.type(), dr.content(), n.id(), d.attempt(), parentIds);
            committed.add(a);
            log.append("ARTIFACT_CREATED", n.id(), n.role().name(),
                    Json.obj("artifact", a.id(), "hash", a.contentHash(), "parents", parentIds, "status", a.status()));
        }

        Map<ArtifactType, String> outputs = new LinkedHashMap<>();
        for (ArtifactDraft dr : drafts) outputs.put(dr.type(), dr.content());
        GateReport report = evaluateGate("EXIT", n, concat(GateChecks.outputsMatchContract(), n.exitChecks()),
                new GateContext(n.id(), n.inputs(), inputs, n.outputs(), outputs));
        if (!report.passed()) {
            for (Artifact a : committed) {
                store.setStatus(a.id(), ArtifactStatus.REJECTED);
                log.append("ARTIFACT_REJECTED", n.id(), "gate-evaluator", Json.obj("artifact", a.id()));
            }
            fail(n, "exit gate failed: " + report.failureSummary());
            return;
        }
        for (Artifact a : committed) {
            store.setStatus(a.id(), ArtifactStatus.VALID);
            log.append("ARTIFACT_VALIDATED", n.id(), "gate-evaluator", Json.obj("artifact", a.id()));
        }
        for (AgentDecision ad : d.result().decisions()) {
            List<String> refs = committed.stream().filter(a -> a.type() == ad.about()).map(Artifact::id).toList();
            recordDecision(n.id(), "GENERATE", ad.rationale(), n.role().name(), refs);
        }
        transition(n, NodeState.SUCCEEDED, "exit gate passed");
    }

    private void onApprovalDone(ApprovalView v) {
        Node n = dag.node(v.request().nodeId());
        humanWaitMs += Duration.between(v.request().requestedAt(), v.decidedAt()).toMillis();
        recordDecision(n.id(), "APPROVAL_" + v.status(), v.rationale(), v.approver(), v.request().artifactRefs());
        if (status != RunStatus.ACTIVE || n.state() != NodeState.AWAITING_APPROVAL) {
            log.append("APPROVAL_IGNORED", n.id(), "orchestrator", Json.obj("approvalId", v.request().id(),
                    "reason", "run no longer active or node not awaiting approval"));
            return;
        }
        if (v.status() == ApprovalStatus.APPROVED) {
            if (n.kind() == NodeKind.APPROVAL) {
                n.setEndedAt(Instant.now());
                transition(n, NodeState.SUCCEEDED, "approved by " + v.approver());
            } else {
                n.setPolicyApproved(true);
                transition(n, NodeState.PENDING, "policy approval granted by " + v.approver() + "; re-queued");
            }
        } else if (v.status() == ApprovalStatus.REJECTED) {
            n.setEndedAt(Instant.now());
            transition(n, NodeState.ABORTED, "rejected by " + v.approver());
            stop(RunStatus.ABORTED, "approval rejected at " + n.id() + " by " + v.approver() + ": " + v.rationale());
        }
    }

    // ------------------------------------------------------------------ gates, failure, stop

    private boolean entryGatePasses(Node n, List<Artifact> inputs) {
        GateReport r = evaluateGate("ENTRY", n, concat(GateChecks.inputsValid(), n.entryChecks()),
                new GateContext(n.id(), n.inputs(), inputs, n.outputs(), Map.of()));
        if (!r.passed()) block(n, "entry gate failed: " + r.failureSummary());
        return r.passed();
    }

    private GateReport evaluateGate(String phase, Node n, List<NamedCheck> checks, GateContext ctx) {
        GateReport r = Gate.evaluate(checks, ctx);
        List<Map<String, Object>> results = r.results().stream()
                .map(c -> Json.obj("check", c.name(), "passed", c.passed(), "detail", c.detail())).toList();
        log.append("GATE_EVALUATED", n.id(), "gate-evaluator", Json.obj("phase", phase, "passed", r.passed(), "checks", results));
        return r;
    }

    private void block(Node n, String reason) {
        n.setEndedAt(Instant.now());
        transition(n, NodeState.BLOCKED, reason);
        stop(RunStatus.SAFE_STOPPED, n.id() + " blocked: " + reason);
    }

    private void fail(Node n, String reason) {
        transition(n, NodeState.FAILED, reason);
        stop(RunStatus.SAFE_STOPPED, n.id() + " failed: " + reason);
    }

    /** First reason wins. Halts scheduling, cancels open approvals, parks every not-yet-started node. */
    private void stop(RunStatus newStatus, String reason) {
        if (status != RunStatus.ACTIVE) return;
        status = newStatus;
        stopReason = reason;
        log.append(newStatus == RunStatus.ABORTED ? "RUN_ABORTED" : "SAFE_STOP", null, "orchestrator", Json.obj("reason", reason));
        approvals.cancelAll("run stopped: " + reason);
        for (Node n : dag.nodes()) {
            switch (n.state()) {
                case PENDING, READY, AWAITING_APPROVAL -> transition(n, NodeState.SAFE_STOPPED, "run stopped");
                default -> { }
            }
        }
        snapshot();
    }

    private void recordDecision(String nodeId, String kind, String rationale, String actor, List<String> refs) {
        var d = context.addDecision(nodeId, kind, rationale, actor, refs);
        log.append("DECISION_RECORDED", nodeId, actor, Json.obj("decisionId", d.id(), "kind", kind,
                "rationale", rationale, "artifacts", refs));
    }

    private void transition(Node n, NodeState to, String reason) {
        NodeState from = StateMachine.apply(n, to);
        log.append("NODE_STATE_CHANGED", n.id(), "orchestrator",
                Json.obj("from", from, "to", to, "reason", reason, "attempt", n.attempt()));
        snapshot();
    }

    // ------------------------------------------------------------------ helpers

    private List<Artifact> resolveInputs(Node n) {
        List<Artifact> out = new ArrayList<>();
        for (ArtifactType t : n.inputs()) store.latestValid(t).ifPresent(out::add);
        return out;
    }

    private Map<ArtifactType, String> contentOf(List<Artifact> artifacts) {
        Map<ArtifactType, String> m = new LinkedHashMap<>();
        for (Artifact a : artifacts) m.put(a.type(), store.content(a.id()));
        return m;
    }

    private static List<NamedCheck> concat(NamedCheck first, List<NamedCheck> rest) {
        List<NamedCheck> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private boolean allSucceeded() {
        return dag.nodes().stream().allMatch(n -> n.state() == NodeState.SUCCEEDED || n.state() == NodeState.SKIPPED);
    }

    private long awaitingApproval() {
        return dag.nodes().stream().filter(n -> n.state() == NodeState.AWAITING_APPROVAL).count();
    }

    private RunResult finish() {
        executor.shutdown();
        long latency = Duration.between(startedAt, Instant.now()).toMillis();
        log.append("RUN_FINISHED", null, "orchestrator", Json.obj("status", status, "reason", stopReason, "latencyMs", latency));
        snapshot();
        Map<String, NodeState> states = new LinkedHashMap<>();
        for (Node n : dag.nodes()) states.put(n.id(), n.state());
        long busy = dag.nodes().stream().mapToLong(Node::runMillis).sum();
        return new RunResult(runId, status, stopReason, latency, busy, humanWaitMs, peak, approvalsRequested, states);
    }

    /** Persists node states after every transition (atomic replace) so an operator can see where a run stands. */
    private void snapshot() {
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (Node n : dag.nodes()) nodes.put(n.id(), Json.obj("kind", n.kind(), "state", n.state(), "attempt", n.attempt()));
        String json = Json.write(Json.obj("runId", runId, "status", status, "reason", stopReason,
                "updatedAt", Instant.now(), "nodes", nodes));
        try {
            Files.createDirectories(runDir);
            Path tmp = runDir.resolve("state.json.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, runDir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
