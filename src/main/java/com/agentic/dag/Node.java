package com.agentic.dag;

import com.agentic.gate.NamedCheck;
import com.agentic.model.AgentRole;
import com.agentic.model.Artifact;
import com.agentic.model.ArtifactType;
import com.agentic.model.RiskTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A unit of work. Definition fields are fixed after graph construction; runtime fields are mutated
 * only by the orchestrator's single coordinator thread (state is volatile so status endpoints can read it).
 */
public final class Node {
    private final String id;
    private final NodeKind kind;
    private final AgentRole role;
    private final RiskTier risk;
    private final List<ArtifactType> inputs = new ArrayList<>();
    private final List<ArtifactType> outputs = new ArrayList<>();
    private final List<String> allowedPaths = new ArrayList<>();
    private final List<NamedCheck> entryChecks = new ArrayList<>();
    private final List<NamedCheck> exitChecks = new ArrayList<>();

    private volatile NodeState state = NodeState.PENDING;
    private int attempt;
    private Instant startedAt;
    private Instant endedAt;
    private long runMillis;
    private boolean policyApproved;
    private String inputHash;
    private List<Artifact> consumedInputs = List.of();

    private Node(String id, NodeKind kind, AgentRole role, RiskTier risk) {
        this.id = id;
        this.kind = kind;
        this.role = role;
        this.risk = risk;
    }

    public static Node agent(String id, AgentRole role, RiskTier risk) { return new Node(id, NodeKind.AGENT, role, risk); }
    public static Node approval(String id, RiskTier risk) { return new Node(id, NodeKind.APPROVAL, AgentRole.HUMAN, risk); }
    public static Node sync(String id) { return new Node(id, NodeKind.SYNC, AgentRole.NONE, RiskTier.LOW); }
    public static Node finalNode(String id) { return new Node(id, NodeKind.FINAL, AgentRole.NONE, RiskTier.LOW); }

    public Node in(ArtifactType... types) { inputs.addAll(Arrays.asList(types)); return this; }
    public Node out(ArtifactType... types) { outputs.addAll(Arrays.asList(types)); return this; }
    public Node paths(String... p) { allowedPaths.addAll(Arrays.asList(p)); return this; }
    public Node entry(NamedCheck... c) { entryChecks.addAll(Arrays.asList(c)); return this; }
    public Node exit(NamedCheck... c) { exitChecks.addAll(Arrays.asList(c)); return this; }

    public String id() { return id; }
    public NodeKind kind() { return kind; }
    public AgentRole role() { return role; }
    public RiskTier risk() { return risk; }
    public List<ArtifactType> inputs() { return Collections.unmodifiableList(inputs); }
    public List<ArtifactType> outputs() { return Collections.unmodifiableList(outputs); }
    public List<String> allowedPaths() { return Collections.unmodifiableList(allowedPaths); }
    public List<NamedCheck> entryChecks() { return Collections.unmodifiableList(entryChecks); }
    public List<NamedCheck> exitChecks() { return Collections.unmodifiableList(exitChecks); }

    public NodeState state() { return state; }
    void setState(NodeState s) { this.state = s; }
    public int attempt() { return attempt; }
    public int incrementAttempt() { return ++attempt; }
    public Instant startedAt() { return startedAt; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public Instant endedAt() { return endedAt; }
    public void setEndedAt(Instant t) { this.endedAt = t; }
    public long runMillis() { return runMillis; }
    public void addRunMillis(long ms) { this.runMillis += ms; }
    public boolean policyApproved() { return policyApproved; }
    public void setPolicyApproved(boolean v) { this.policyApproved = v; }
    public String inputHash() { return inputHash; }
    public void setInputHash(String h) { this.inputHash = h; }
    public List<Artifact> consumedInputs() { return consumedInputs; }
    public void setConsumedInputs(List<Artifact> a) { this.consumedInputs = List.copyOf(a); }

    @Override public String toString() { return id + "[" + state + "]"; }
}
