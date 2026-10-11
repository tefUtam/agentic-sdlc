package com.agentic.dag;

import java.util.ArrayList;
import java.util.List;

/** Computes which nodes may start. Pure function of node states and edge kinds. */
public final class Scheduler {
    private Scheduler() {}

    public static boolean isReady(Dag dag, Node n) {
        if (n.state() != NodeState.PENDING) return false;
        for (Edge e : dag.incoming(n.id())) {
            if (!e.kind().requiresSuccess()) continue; // SOFT edges are advisory
            NodeState s = dag.node(e.from()).state();
            if (s != NodeState.SUCCEEDED && s != NodeState.SKIPPED) return false;
        }
        return true;
    }

    public static List<Node> readyNodes(Dag dag) {
        List<Node> out = new ArrayList<>();
        for (Node n : dag.nodes()) if (isReady(dag, n)) out.add(n);
        return out;
    }
}
