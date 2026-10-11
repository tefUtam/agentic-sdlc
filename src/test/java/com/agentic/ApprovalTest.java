package com.agentic;

import com.agentic.approval.ApprovalManager;
import com.agentic.approval.ApprovalRequest;
import com.agentic.approval.ApprovalStatus;
import com.agentic.approval.ApprovalView;
import com.agentic.approval.CliApprover;
import com.agentic.store.EventLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class ApprovalTest {
    private final EventLog log = new EventLog(null);
    private final ApprovalManager mgr = new ApprovalManager(log);

    private ApprovalRequest request() {
        return mgr.request("run-1", "REQ_APPROVE", "hash-abc", "sign off", List.of("REQUIREMENT_SPEC:v1"), List.of("HIGH"));
    }

    public void testRequestThenApproveNotifiesListenerAndLogs() {
        List<ApprovalView> seen = new ArrayList<>();
        mgr.setListener(seen::add);
        ApprovalRequest r = request();
        Assert.eq(1, mgr.pending().size());
        ApprovalView v = mgr.decide(r.id(), ApprovalStatus.APPROVED, "alice", "looks right", "hash-abc");
        Assert.eq(ApprovalStatus.APPROVED, v.status());
        Assert.eq("alice", v.approver());
        Assert.eq(0, mgr.pending().size());
        Assert.eq(1, seen.size());
        Assert.eq(1L, log.count("APPROVAL_REQUESTED"));
        Assert.eq(1L, log.count("APPROVAL_DECIDED"));
    }

    public void testApprovalIsBoundToTheArtifactHash() {
        ApprovalRequest r = request();
        Assert.throwsEx(IllegalStateException.class, () -> mgr.decide(r.id(), ApprovalStatus.APPROVED, "alice", "", "some-other-hash"));
        Assert.eq(ApprovalStatus.PENDING, mgr.get(r.id()).orElseThrow().status()); // stale approval changed nothing
    }

    public void testDecisionsAreFinal() {
        ApprovalRequest r = request();
        mgr.decide(r.id(), ApprovalStatus.REJECTED, "bob", "no", null);
        Assert.throwsEx(IllegalStateException.class, () -> mgr.decide(r.id(), ApprovalStatus.APPROVED, "bob", "changed my mind", null));
    }

    public void testInvalidDecisionsAreRefused() {
        ApprovalRequest r = request();
        Assert.throwsEx(NoSuchElementException.class, () -> mgr.decide("APR-999", ApprovalStatus.APPROVED, "a", "", null));
        Assert.throwsEx(UnsupportedOperationException.class, () -> mgr.decide(r.id(), ApprovalStatus.CHANGES_REQUESTED, "a", "", null));
        Assert.throwsEx(IllegalArgumentException.class, () -> mgr.decide(r.id(), ApprovalStatus.APPROVED, " ", "", null));
        Assert.throwsEx(IllegalArgumentException.class, () -> mgr.decide(r.id(), ApprovalStatus.PENDING, "a", "", null));
    }

    public void testCancelAllOnlyTouchesPending() {
        ApprovalRequest a = request();
        ApprovalRequest b = request();
        mgr.decide(a.id(), ApprovalStatus.APPROVED, "alice", "", null);
        mgr.cancelAll("run stopped");
        Assert.eq(ApprovalStatus.APPROVED, mgr.get(a.id()).orElseThrow().status());
        Assert.eq(ApprovalStatus.CANCELLED, mgr.get(b.id()).orElseThrow().status());
    }

    public void testCliApproverReadsDecisionFromStream() throws Exception {
        ApprovalRequest r = request();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CliApprover.start(mgr, new ByteArrayInputStream("a\n".getBytes()), new PrintStream(sink), "cli-tester");
        long deadline = System.currentTimeMillis() + 3000;
        while (mgr.pending().size() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        ApprovalView v = mgr.get(r.id()).orElseThrow();
        Assert.eq(ApprovalStatus.APPROVED, v.status());
        Assert.eq("cli-tester", v.approver());
        Assert.contains(sink.toString(), "APPROVAL NEEDED " + r.id());
    }
}
