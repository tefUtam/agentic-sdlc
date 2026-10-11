package com.agentic.dag;

import com.agentic.model.ArtifactType;
import com.agentic.model.RiskTier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit dependency graph. Structure is data; {@link #validate()} enforces the plan-time invariants. */
public final class Dag {
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    public Dag addNode(Node n) {
        if (nodes.putIfAbsent(n.id(), n) != null) throw new IllegalArgumentException("duplicate node id: " + n.id());
        return this;
    }

    public Dag edge(String from, String to) { return edge(from, to, EdgeKind.HARD); }

    public Dag edge(String from, String to, EdgeKind kind) {
        edges.add(new Edge(from, to, kind));
        return this;
    }

    public Node node(String id) {
        Node n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("unknown node: " + id);
        return n;
    }

    public Collection<Node> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public List<Edge> edges() { return Collections.unmodifiableList(edges); }

    public List<Edge> incoming(String id) { return edges.stream().filter(e -> e.to().equals(id)).toList(); }
    public List<Edge> outgoing(String id) { return edges.stream().filter(e -> e.from().equals(id)).toList(); }

    public Set<String> ancestors(String id) { return reach(id, true); }
    public Set<String> descendants(String id) { return reach(id, false); }

    private Set<String> reach(String start, boolean backwards) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (Edge e : edges) {
                String next = backwards ? (e.to().equals(cur) ? e.from() : null) : (e.from().equals(cur) ? e.to() : null);
                if (next != null && seen.add(next)) stack.push(next);
            }
        }
        seen.remove(start);
        return seen;
    }

    /** Kahn's algorithm; throws if the graph has a cycle. Order is stable (insertion order among ready nodes). */
    public List<String> topologicalOrder() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (String id : nodes.keySet()) indeg.put(id, 0);
        for (Edge e : edges) indeg.merge(e.to(), 1, Integer::sum);
        Deque<String> ready = new ArrayDeque<>();
        indeg.forEach((id, d) -> { if (d == 0) ready.add(id); });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (Edge e : edges) {
                if (e.from().equals(id) && indeg.merge(e.to(), -1, Integer::sum) == 0) ready.add(e.to());
            }
        }
        if (order.size() != nodes.size()) {
            Set<String> rest = new LinkedHashSet<>(nodes.keySet());
            rest.removeAll(order);
            throw new DagValidationException(List.of("cycle detected involving " + rest));
        }
        return order;
    }

    /** Enforces the plan-time invariants from the design doc (section 4.4). Throws with all problems found. */
    public void validate() {
        List<String> problems = new ArrayList<>();

        // Structural sanity first; later checks assume it.
        Set<String> seenEdges = new HashSet<>();
        for (Edge e : edges) {
            if (!nodes.containsKey(e.from())) problems.add("edge references unknown node " + e.from());
            if (!nodes.containsKey(e.to())) problems.add("edge references unknown node " + e.to());
            if (e.from().equals(e.to())) problems.add("self-loop on " + e.from());
            if (!seenEdges.add(e.from() + "->" + e.to())) problems.add("duplicate edge " + e.from() + "->" + e.to());
        }
        if (!problems.isEmpty()) throw new DagValidationException(problems);

        // Invariant 1: acyclic (throws its own DagValidationException).
        topologicalOrder();

        // Invariant 2: each artifact type has exactly one producer, and every input has that producer upstream.
        Map<ArtifactType, String> producer = new EnumMap<>(ArtifactType.class);
        for (Node n : nodes.values()) {
            for (ArtifactType t : n.outputs()) {
                String prev = producer.putIfAbsent(t, n.id());
                if (prev != null) problems.add("artifact " + t + " produced by both " + prev + " and " + n.id());
            }
        }
        Map<String, Set<String>> anc = new HashMap<>();
        for (Node n : nodes.values()) anc.put(n.id(), ancestors(n.id()));
        for (Node n : nodes.values()) {
            for (ArtifactType t : n.inputs()) {
                String p = producer.get(t);
                if (p == null) problems.add(n.id() + " needs " + t + " but no node produces it");
                else if (!anc.get(n.id()).contains(p)) problems.add(n.id() + " needs " + t + " but producer " + p + " is not upstream");
            }
        }

        // Invariant 3: every HIGH-risk agent node has a human approval node upstream.
        for (Node n : nodes.values()) {
            if (n.kind() == NodeKind.AGENT && n.risk() == RiskTier.HIGH
                    && anc.get(n.id()).stream().noneMatch(a -> nodes.get(a).kind() == NodeKind.APPROVAL)) {
                problems.add("HIGH-risk node " + n.id() + " has no approval node upstream");
            }
        }

        // Invariant 4: nodes that can run concurrently must have disjoint write scopes.
        List<Node> list = new ArrayList<>(nodes.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Node a = list.get(i), b = list.get(j);
                boolean ordered = anc.get(a.id()).contains(b.id()) || anc.get(b.id()).contains(a.id());
                if (!ordered && pathsOverlap(a.allowedPaths(), b.allowedPaths())) {
                    problems.add("concurrent nodes " + a.id() + " and " + b.id() + " have overlapping write scopes");
                }
            }
        }

        // Invariant 5: exactly one sink, and it is the FINAL node; SYNC barriers join at least two branches.
        List<String> sinks = nodes.keySet().stream().filter(id -> outgoing(id).isEmpty()).toList();
        if (sinks.size() != 1) problems.add("graph must have exactly one sink, found " + sinks);
        else if (nodes.get(sinks.get(0)).kind() != NodeKind.FINAL) problems.add("sink " + sinks.get(0) + " must be a FINAL node");
        for (Node n : nodes.values()) {
            if (n.kind() == NodeKind.FINAL && !outgoing(n.id()).isEmpty()) problems.add("FINAL node " + n.id() + " has outgoing edges");
            if (n.kind() == NodeKind.SYNC && incoming(n.id()).size() < 2) problems.add("SYNC node " + n.id() + " joins fewer than 2 branches");
        }

        if (!problems.isEmpty()) throw new DagValidationException(problems);
    }

    static boolean pathsOverlap(List<String> as, List<String> bs) {
        for (String a : as) for (String b : bs) {
            String x = a.endsWith("/") ? a : a + "/";
            String y = b.endsWith("/") ? b : b + "/";
            if (x.startsWith(y) || y.startsWith(x)) return true;
        }
        return false;
    }
}
