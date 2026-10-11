package com.agentic.approval;

import java.time.Instant;
import java.util.List;

/** A request is bound to the content hash of what the human is being asked to approve. */
public record ApprovalRequest(String id, String runId, String nodeId, String artifactHash, String summary,
                              List<String> artifactRefs, List<String> riskReasons, Instant requestedAt) {}
