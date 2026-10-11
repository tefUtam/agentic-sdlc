package com.agentic.approval;

import java.util.function.Function;

/** Demo/test approver: polls pending approvals and decides them through the normal ApprovalManager API. */
public final class AutoApprover {
    private final Thread thread;
    private volatile boolean running = true;

    private AutoApprover(ApprovalManager mgr, Function<ApprovalRequest, ApprovalStatus> decider, String approver) {
        thread = new Thread(() -> {
            while (running) {
                for (ApprovalView v : mgr.pending()) {
                    try {
                        mgr.decide(v.request().id(), decider.apply(v.request()), approver,
                                "auto-decision (demo mode)", v.request().artifactHash());
                    } catch (RuntimeException ignored) {
                        // already decided or cancelled by another actor
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "auto-approver");
        thread.setDaemon(true);
    }

    public static AutoApprover start(ApprovalManager mgr, Function<ApprovalRequest, ApprovalStatus> decider, String approver) {
        AutoApprover a = new AutoApprover(mgr, decider, approver);
        a.thread.start();
        return a;
    }

    public void stop() {
        running = false;
        thread.interrupt();
    }
}
