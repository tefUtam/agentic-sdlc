package com.agentic.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

/** Interactive approver: prints each pending request and reads a/r from the given stream. */
public final class CliApprover {
    private CliApprover() {}

    public static Thread start(ApprovalManager mgr, InputStream in, PrintStream out, String approver) {
        Thread t = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            Set<String> prompted = new HashSet<>();
            try {
                while (true) {
                    for (ApprovalView v : mgr.pending()) {
                        if (!prompted.add(v.request().id())) continue;
                        out.println();
                        out.println("APPROVAL NEEDED " + v.request().id() + " at node " + v.request().nodeId());
                        out.println("  " + v.request().summary());
                        out.println("  artifacts: " + v.request().artifactRefs() + "  risk: " + v.request().riskReasons());
                        out.print("  [a]pprove / [r]eject > ");
                        out.flush();
                        String line = reader.readLine();
                        if (line == null) return;
                        ApprovalStatus s = line.trim().toLowerCase().startsWith("a") ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
                        try {
                            mgr.decide(v.request().id(), s, approver, "decided via CLI", v.request().artifactHash());
                        } catch (RuntimeException e) {
                            out.println("  could not record decision: " + e.getMessage());
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException e) {
                // stop quietly
            }
        }, "cli-approver");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
