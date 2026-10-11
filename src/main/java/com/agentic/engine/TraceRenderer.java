package com.agentic.engine;

import com.agentic.dag.Node;
import com.agentic.model.Artifact;

/** Human-readable run summary: node table, artifact lineage, and audit-chain verification. */
public final class TraceRenderer {
    private TraceRenderer() {}

    public static String render(Orchestrator o, Orchestrator.RunResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%nRun %s  status=%s%n", r.runId(), r.status()));
        if (r.reason() != null) sb.append("Reason: ").append(r.reason()).append('\n');
        sb.append(String.format("Latency %d ms (agent busy %d ms, human wait %d ms) | peak parallelism %d | approvals requested %d%n",
                r.latencyMs(), r.agentBusyMs(), r.humanWaitMs(), r.peakParallelism(), r.approvalsRequested()));
        sb.append(String.format("%n%-19s %-9s %-14s %-8s %s%n", "NODE", "KIND", "STATE", "ATTEMPT", "AGENT ms"));
        for (Node n : o.dag().nodes()) {
            sb.append(String.format("%-19s %-9s %-14s %-8d %d%n", n.id(), n.kind(), n.state(), n.attempt(), n.runMillis()));
        }
        sb.append(String.format("%nARTIFACT LINEAGE (artifact <- direct parents)%n"));
        for (Artifact a : o.store().all()) {
            sb.append(String.format("%-28s %-9s <- %s%n", a.id(), a.status(), a.parents().isEmpty() ? "(none)" : String.join(", ", a.parents())));
        }
        sb.append(String.format("%nDecisions recorded: %d | Audit events: %d | Hash chain verified: %s%n",
                o.context().decisions().size(), o.eventLog().events().size(), o.eventLog().verifyChain()));
        sb.append("Run directory: ").append(o.runDir()).append('\n');
        return sb.toString();
    }
}
