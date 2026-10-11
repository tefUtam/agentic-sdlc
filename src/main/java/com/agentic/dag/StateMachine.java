package com.agentic.dag;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.agentic.dag.NodeState.*;

/** Single source of truth for legal node state transitions. Illegal moves throw. */
public final class StateMachine {
    private static final Map<NodeState, Set<NodeState>> ALLOWED = new EnumMap<>(NodeState.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(READY, SKIPPED, SAFE_STOPPED));
        ALLOWED.put(READY, EnumSet.of(RUNNING, AWAITING_APPROVAL, SUCCEEDED, BLOCKED, SAFE_STOPPED));
        ALLOWED.put(AWAITING_APPROVAL, EnumSet.of(PENDING, SUCCEEDED, ABORTED, SAFE_STOPPED));
        ALLOWED.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, RETRYING, SAFE_STOPPED));
        ALLOWED.put(RETRYING, EnumSet.of(RUNNING, FAILED, SAFE_STOPPED));
        ALLOWED.put(FAILED, EnumSet.of(RETRYING, SAFE_STOPPED));
        ALLOWED.put(BLOCKED, EnumSet.of(READY, SAFE_STOPPED));
        ALLOWED.put(SUCCEEDED, EnumSet.of(STALE));
        ALLOWED.put(STALE, EnumSet.of(PENDING));
        ALLOWED.put(SKIPPED, EnumSet.noneOf(NodeState.class));
        ALLOWED.put(ABORTED, EnumSet.noneOf(NodeState.class));
        ALLOWED.put(SAFE_STOPPED, EnumSet.noneOf(NodeState.class));
    }

    private StateMachine() {}

    public static boolean canTransition(NodeState from, NodeState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void require(NodeState from, NodeState to) {
        if (!canTransition(from, to)) throw new IllegalStateException("illegal transition " + from + " -> " + to);
    }

    /** Validated transition: the only way runtime code should change a node's state. Returns the previous state. */
    public static NodeState apply(Node node, NodeState to) {
        NodeState from = node.state();
        require(from, to);
        node.setState(to);
        return from;
    }

    public static Set<NodeState> allowedFrom(NodeState from) {
        return Set.copyOf(ALLOWED.get(from));
    }
}
