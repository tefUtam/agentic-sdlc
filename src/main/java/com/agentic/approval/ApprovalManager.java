package com.agentic.approval;

import com.agentic.store.EventLog;
import com.agentic.store.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Thread-safe approval registry. Humans decide from any thread (CLI, HTTP); the decision is forwarded to
 * a listener (the orchestrator's inbox) so state transitions still happen on one coordinator thread.
 */
public final class ApprovalManager {
    private final EventLog log;
    private final Map<String, ApprovalView> views = new LinkedHashMap<>();
    private volatile Consumer<ApprovalView> listener;
    private int seq;

    public ApprovalManager(EventLog log) { this.log = log; }

    public void setListener(Consumer<ApprovalView> l) { this.listener = l; }

    public synchronized ApprovalRequest request(String runId, String nodeId, String artifactHash, String summary,
                                                List<String> artifactRefs, List<String> riskReasons) {
        String id = String.format("APR-%03d", ++seq);
        ApprovalRequest r = new ApprovalRequest(id, runId, nodeId, artifactHash, summary, List.copyOf(artifactRefs),
                List.copyOf(riskReasons), Instant.now());
        views.put(id, new ApprovalView(r, ApprovalStatus.PENDING, null, null, null));
        log.append("APPROVAL_REQUESTED", nodeId, "approval-manager",
                Json.obj("approvalId", id, "artifactHash", artifactHash, "summary", summary, "riskReasons", riskReasons));
        return r;
    }

    public synchronized List<ApprovalView> pending() {
        List<ApprovalView> out = new ArrayList<>();
        for (ApprovalView v : views.values()) if (v.status() == ApprovalStatus.PENDING) out.add(v);
        return out;
    }

    public synchronized List<ApprovalView> all() { return List.copyOf(views.values()); }

    public synchronized Optional<ApprovalView> get(String id) { return Optional.ofNullable(views.get(id)); }

    /**
     * @throws NoSuchElementException        unknown approval id
     * @throws IllegalStateException         not pending, or the artifact hash no longer matches
     * @throws UnsupportedOperationException REQUEST_CHANGES (needs retry and re-plan; not in this slice)
     * @throws IllegalArgumentException      missing approver or a non-decision status
     */
    public ApprovalView decide(String id, ApprovalStatus decision, String approver, String rationale, String expectedHash) {
        ApprovalView updated;
        synchronized (this) {
            ApprovalView cur = views.get(id);
            if (cur == null) throw new NoSuchElementException("unknown approval " + id);
            if (decision == ApprovalStatus.CHANGES_REQUESTED) throw new UnsupportedOperationException("REQUEST_CHANGES is not implemented yet");
            if (decision != ApprovalStatus.APPROVED && decision != ApprovalStatus.REJECTED) throw new IllegalArgumentException("not a decision: " + decision);
            if (approver == null || approver.isBlank()) throw new IllegalArgumentException("approver is required");
            if (cur.status() != ApprovalStatus.PENDING) throw new IllegalStateException(id + " already " + cur.status());
            if (expectedHash != null && !expectedHash.equals(cur.request().artifactHash())) {
                throw new IllegalStateException("artifact changed since the request was made; refusing stale approval");
            }
            updated = new ApprovalView(cur.request(), decision, approver, rationale == null ? "" : rationale, Instant.now());
            views.put(id, updated);
            log.append("APPROVAL_DECIDED", cur.request().nodeId(), approver,
                    Json.obj("approvalId", id, "decision", decision, "rationale", updated.rationale(),
                            "artifactHash", cur.request().artifactHash()));
        }
        Consumer<ApprovalView> l = listener;
        if (l != null) l.accept(updated);
        return updated;
    }

    public synchronized void cancelAll(String reason) {
        for (ApprovalView v : new ArrayList<>(views.values())) {
            if (v.status() != ApprovalStatus.PENDING) continue;
            views.put(v.request().id(), new ApprovalView(v.request(), ApprovalStatus.CANCELLED, "system", reason, Instant.now()));
            log.append("APPROVAL_CANCELLED", v.request().nodeId(), "approval-manager",
                    Json.obj("approvalId", v.request().id(), "reason", reason));
        }
    }
}
