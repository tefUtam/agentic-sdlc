package com.agentic;

import com.agentic.dag.Dag;
import com.agentic.dag.DagValidationException;
import com.agentic.dag.Edge;
import com.agentic.dag.EdgeKind;
import com.agentic.dag.Node;
import com.agentic.dag.NodeState;
import com.agentic.dag.Scheduler;
import com.agentic.dag.StateMachine;
import com.agentic.engine.SdlcGraphFactory;

import java.util.List;

import static com.agentic.model.AgentRole.*;
import static com.agentic.model.ArtifactType.*;
import static com.agentic.model.RiskTier.*;

public class DagTest {

    private static Dag diamond(String bPaths, String cPaths) {
        return new Dag()
                .addNode(Node.agent("A", REQUIREMENTS, LOW))
                .addNode(Node.agent("B", ARCHITECT, LOW).paths(bPaths))
                .addNode(Node.agent("C", ARCHITECT, LOW).paths(cPaths))
                .addNode(Node.sync("J"))
                .addNode(Node.finalNode("END"))
                .edge("A", "B").edge("A", "C")
                .edge("B", "J", EdgeKind.SYNC).edge("C", "J", EdgeKind.SYNC)
                .edge("J", "END");
    }

    private static List<String> problems(Dag d) {
        try {
            d.validate();
        } catch (DagValidationException e) {
            return e.problems();
        }
        throw new AssertionError("expected validation to fail");
    }

    private static void succeed(Node n) {
        StateMachine.apply(n, NodeState.READY);
        StateMachine.apply(n, NodeState.RUNNING);
        StateMachine.apply(n, NodeState.SUCCEEDED);
    }

    public void testStandardGraphIsValid() {
        Dag d = SdlcGraphFactory.greenfield();
        d.validate();
        Assert.eq(19, d.nodes().size());
    }

    public void testTopologicalOrderRespectsEveryEdge() {
        Dag d = SdlcGraphFactory.greenfield();
        List<String> order = d.topologicalOrder();
        for (Edge e : d.edges()) Assert.check(order.indexOf(e.from()) < order.indexOf(e.to()), e + " out of order");
    }

    public void testCycleIsRejected() {
        Dag d = new Dag().addNode(Node.agent("A", TESTER, LOW)).addNode(Node.agent("B", TESTER, LOW)).edge("A", "B").edge("B", "A");
        Assert.contains(problems(d).get(0), "cycle");
    }

    public void testUnknownNodeAndSelfLoopAreRejected() {
        Dag d = new Dag().addNode(Node.agent("A", TESTER, LOW)).edge("A", "GHOST").edge("A", "A");
        List<String> p = problems(d);
        Assert.check(p.stream().anyMatch(s -> s.contains("unknown node GHOST")), "unknown node: " + p);
        Assert.check(p.stream().anyMatch(s -> s.contains("self-loop")), "self-loop: " + p);
    }

    public void testInputWithoutProducerIsRejected() {
        Dag d = new Dag().addNode(Node.agent("A", ARCHITECT, LOW).in(REQUIREMENT_SPEC)).addNode(Node.finalNode("END")).edge("A", "END");
        Assert.check(problems(d).stream().anyMatch(s -> s.contains("no node produces")), "missing producer");
    }

    public void testProducerThatIsNotUpstreamIsRejected() {
        Dag d = new Dag()
                .addNode(Node.agent("P", REQUIREMENTS, LOW).out(REQUIREMENT_SPEC))
                .addNode(Node.agent("Q", ARCHITECT, LOW).in(REQUIREMENT_SPEC))
                .addNode(Node.sync("J")).addNode(Node.finalNode("END"))
                .edge("P", "J", EdgeKind.SYNC).edge("Q", "J", EdgeKind.SYNC).edge("J", "END");
        Assert.check(problems(d).stream().anyMatch(s -> s.contains("not upstream")), "producer not upstream");
    }

    public void testTwoProducersOfOneArtifactAreRejected() {
        Dag d = new Dag()
                .addNode(Node.agent("P1", REQUIREMENTS, LOW).out(ARCH_DOC))
                .addNode(Node.agent("P2", ARCHITECT, LOW).out(ARCH_DOC))
                .addNode(Node.sync("J")).addNode(Node.finalNode("END"))
                .edge("P1", "J", EdgeKind.SYNC).edge("P2", "J", EdgeKind.SYNC).edge("J", "END");
        Assert.check(problems(d).stream().anyMatch(s -> s.contains("produced by both")), "duplicate producer");
    }

    public void testHighRiskAgentNodeNeedsUpstreamApproval() {
        Dag bad = new Dag().addNode(Node.agent("A", REQUIREMENTS, LOW)).addNode(Node.agent("H", IMPLEMENTER, HIGH))
                .addNode(Node.finalNode("END")).edge("A", "H").edge("H", "END");
        Assert.check(problems(bad).stream().anyMatch(s -> s.contains("HIGH-risk node H")), "needs approval");

        Dag good = new Dag().addNode(Node.agent("A", REQUIREMENTS, LOW)).addNode(Node.approval("OK", HIGH))
                .addNode(Node.agent("H", IMPLEMENTER, HIGH)).addNode(Node.finalNode("END"))
                .edge("A", "OK").edge("OK", "H").edge("H", "END");
        good.validate();
    }

    public void testConcurrentNodesMustHaveDisjointWriteScopes() {
        Assert.check(problems(diamond("src/", "src/main/")).stream().anyMatch(s -> s.contains("overlapping write scopes")), "overlap");
        diamond("src/a/", "src/b/").validate();
    }

    public void testOrderedNodesMayShareWriteScopes() {
        Dag d = new Dag().addNode(Node.agent("A", TESTER, LOW).paths("docs/")).addNode(Node.agent("B", TESTER, LOW).paths("docs/"))
                .addNode(Node.finalNode("END")).edge("A", "B").edge("B", "END");
        d.validate();
    }

    public void testExactlyOneFinalSinkIsRequired() {
        Dag two = new Dag().addNode(Node.agent("A", TESTER, LOW)).addNode(Node.finalNode("E1")).addNode(Node.finalNode("E2"))
                .edge("A", "E1").edge("A", "E2");
        Assert.check(problems(two).stream().anyMatch(s -> s.contains("exactly one sink")), "two sinks");
        Dag notFinal = new Dag().addNode(Node.agent("A", TESTER, LOW)).addNode(Node.agent("B", TESTER, LOW)).edge("A", "B");
        Assert.check(problems(notFinal).stream().anyMatch(s -> s.contains("must be a FINAL node")), "sink kind");
    }

    public void testSyncBarrierMustJoinAtLeastTwoBranches() {
        Dag d = new Dag().addNode(Node.agent("A", TESTER, LOW)).addNode(Node.sync("J")).addNode(Node.finalNode("END"))
                .edge("A", "J", EdgeKind.SYNC).edge("J", "END");
        Assert.check(problems(d).stream().anyMatch(s -> s.contains("joins fewer than 2")), "sync");
    }

    public void testAncestorsAndDescendants() {
        Dag d = diamond("a/", "b/");
        Assert.eq(java.util.Set.of("A", "B", "C"), d.ancestors("J"));
        Assert.eq(java.util.Set.of("J", "END"), d.descendants("B"));
    }

    public void testSchedulerReleasesBranchesAndWaitsAtBarrier() {
        Dag d = diamond("a/", "b/");
        Assert.eq(List.of("A"), Scheduler.readyNodes(d).stream().map(Node::id).toList());
        succeed(d.node("A"));
        Assert.eq(List.of("B", "C"), Scheduler.readyNodes(d).stream().map(Node::id).toList());
        succeed(d.node("B"));
        Assert.check(!Scheduler.isReady(d, d.node("J")), "barrier must wait for C");
        succeed(d.node("C"));
        Assert.check(Scheduler.isReady(d, d.node("J")), "barrier opens when all branches succeeded");
    }

    public void testSoftEdgesDoNotBlock() {
        Dag d = new Dag().addNode(Node.agent("X", TESTER, LOW)).addNode(Node.agent("Y", TESTER, LOW)).edge("X", "Y", EdgeKind.SOFT);
        Assert.check(Scheduler.isReady(d, d.node("Y")), "SOFT predecessor must not block");
    }
}
