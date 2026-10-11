package com.agentic.store;

import java.time.Instant;

/** One audit event. {@code hash} chains to {@code prevHash} so any later edit is detectable. */
public record Event(long seq, Instant ts, String type, String nodeId, String actor, String payloadJson,
                    String prevHash, String hash) {

    public static String computeHash(String prevHash, long seq, Instant ts, String type, String nodeId,
                                     String actor, String payloadJson) {
        return Hashing.sha256(String.join("|", prevHash, Long.toString(seq), ts.toString(), type,
                nodeId == null ? "" : nodeId, actor == null ? "" : actor, payloadJson));
    }

    public String toJson() {
        return "{\"seq\":" + seq + ",\"ts\":" + Json.write(ts.toString()) + ",\"type\":" + Json.write(type)
                + ",\"nodeId\":" + Json.write(nodeId) + ",\"actor\":" + Json.write(actor)
                + ",\"payload\":" + payloadJson + ",\"prevHash\":" + Json.write(prevHash)
                + ",\"hash\":" + Json.write(hash) + "}";
    }
}
