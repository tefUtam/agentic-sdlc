package com.agentic.model;

import java.time.Instant;
import java.util.List;

/** A recorded choice (agent or human) that can be traced back from any artifact. */
public record Decision(String id, String nodeId, String kind, String rationale, String actor,
                       List<String> artifactRefs, Instant ts) {}
