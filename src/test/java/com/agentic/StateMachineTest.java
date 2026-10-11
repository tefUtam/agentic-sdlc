package com.agentic;

import com.agentic.dag.Node;
import com.agentic.dag.NodeState;
import com.agentic.dag.StateMachine;
import com.agentic.model.AgentRole;
import com.agentic.model.RiskTier;

public class StateMachineTest {

    public void testEveryStateIsDefined() {
        for (NodeState s : NodeState.values()) StateMachine.allowedFrom(s); // would NPE if a state had no entry
    }

    public void testHappyPathIsLegal() {
        Node n = Node.agent("N", AgentRole.TESTER, RiskTier.LOW);
        StateMachine.apply(n, NodeState.READY);
        StateMachine.apply(n, NodeState.RUNNING);
        StateMachine.apply(n, NodeState.SUCCEEDED);
        Assert.eq(NodeState.SUCCEEDED, n.state());
    }

    public void testIllegalTransitionsThrowAndLeaveStateUntouched() {
        Node n = Node.agent("N", AgentRole.TESTER, RiskTier.LOW);
        Assert.throwsEx(IllegalStateException.class, () -> StateMachine.apply(n, NodeState.RUNNING)); // must pass READY first
        Assert.eq(NodeState.PENDING, n.state());
        Assert.check(!StateMachine.canTransition(NodeState.SUCCEEDED, NodeState.RUNNING), "no re-run without going STALE");
        Assert.check(!StateMachine.canTransition(NodeState.SAFE_STOPPED, NodeState.PENDING), "safe-stop is final");
    }

    public void testTerminalStatesHaveNoExits() {
        Assert.check(StateMachine.allowedFrom(NodeState.SKIPPED).isEmpty(), "SKIPPED");
        Assert.check(StateMachine.allowedFrom(NodeState.ABORTED).isEmpty(), "ABORTED");
        Assert.check(StateMachine.allowedFrom(NodeState.SAFE_STOPPED).isEmpty(), "SAFE_STOPPED");
    }

    public void testStaleNodesReenterPending() {
        Assert.check(StateMachine.canTransition(NodeState.SUCCEEDED, NodeState.STALE), "SUCCEEDED->STALE");
        Assert.check(StateMachine.canTransition(NodeState.STALE, NodeState.PENDING), "STALE->PENDING");
    }

    public void testApprovedPolicyGatedNodeIsRequeued() {
        Assert.check(StateMachine.canTransition(NodeState.AWAITING_APPROVAL, NodeState.PENDING), "approved node re-queues");
        Assert.check(!StateMachine.canTransition(NodeState.AWAITING_APPROVAL, NodeState.RUNNING), "approval cannot skip the scheduler");
    }
}
