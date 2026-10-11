package com.agentic.approval;

import com.agentic.store.Json;

import java.time.Instant;
import java.util.Map;

public record ApprovalView(ApprovalRequest request, ApprovalStatus status, String approver, String rationale, Instant decidedAt) {

    public Map<String, Object> toMap() {
        return Json.obj("id", request.id(), "runId", request.runId(), "nodeId", request.nodeId(),
                "status", status, "summary", request.summary(), "artifactHash", request.artifactHash(),
                "artifactRefs", request.artifactRefs(), "riskReasons", request.riskReasons(),
                "requestedAt", request.requestedAt(), "approver", approver, "rationale", rationale,
                "decidedAt", decidedAt);
    }
}
