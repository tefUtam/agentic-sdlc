package com.agentic;

import com.agentic.approval.ApprovalManager;
import com.agentic.approval.ApprovalRequest;
import com.agentic.approval.ApprovalServer;
import com.agentic.approval.ApprovalStatus;
import com.agentic.store.EventLog;
import com.agentic.store.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public class ApprovalServerTest {

    private static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient c, String url, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    public void testListDecideAndErrorCodes() throws Exception {
        ApprovalManager mgr = new ApprovalManager(new EventLog(null));
        ApprovalRequest r = mgr.request("run-1", "REQ_APPROVE", "hash-1", "please review", List.of("A:v1"), List.of("HIGH"));
        ApprovalServer server = new ApprovalServer(mgr, () -> Json.obj("status", "ACTIVE"), 0);
        server.start();
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> list = get(c, base + "/approvals");
            Assert.eq(200, list.statusCode());
            Assert.contains(list.body(), r.id());
            Assert.contains(list.body(), "please review");

            Assert.eq(400, post(c, base + "/approvals/" + r.id() + "/decision", "decision=MAYBE&approver=a").statusCode());
            Assert.eq(400, post(c, base + "/approvals/" + r.id() + "/decision", "decision=APPROVE").statusCode()); // approver missing
            Assert.eq(404, post(c, base + "/approvals/APR-404/decision", "decision=APPROVE&approver=a").statusCode());
            Assert.eq(501, post(c, base + "/approvals/" + r.id() + "/decision", "decision=REQUEST_CHANGES&approver=a").statusCode());
            Assert.eq(409, post(c, base + "/approvals/" + r.id() + "/decision", "decision=APPROVE&approver=a&artifactHash=stale").statusCode());
            Assert.eq(ApprovalStatus.PENDING, mgr.get(r.id()).orElseThrow().status());

            HttpResponse<String> ok = post(c, base + "/approvals/" + r.id() + "/decision",
                    "decision=APPROVE&approver=alice+smith&rationale=looks+good%21&artifactHash=hash-1");
            Assert.eq(200, ok.statusCode());
            Assert.eq("alice smith", mgr.get(r.id()).orElseThrow().approver());
            Assert.eq("looks good!", mgr.get(r.id()).orElseThrow().rationale());
            Assert.eq(409, post(c, base + "/approvals/" + r.id() + "/decision", "decision=APPROVE&approver=a").statusCode());

            Assert.eq("[]", get(c, base + "/approvals").body());
            Assert.eq("{\"status\":\"ACTIVE\"}", get(c, base + "/status").body());
            Assert.eq(404, get(c, base + "/nope").statusCode());
        } finally {
            server.stop();
        }
    }

    public void testServerBindsToLoopbackOnly() throws Exception {
        ApprovalServer server = new ApprovalServer(new ApprovalManager(new EventLog(null)), () -> Json.obj(), 0);
        server.start();
        try {
            // The server was created on the loopback address; verify via the bound socket address.
            java.lang.reflect.Field f = ApprovalServer.class.getDeclaredField("server");
            f.setAccessible(true);
            com.sun.net.httpserver.HttpServer hs = (com.sun.net.httpserver.HttpServer) f.get(server);
            Assert.check(hs.getAddress().getAddress().isLoopbackAddress(), "must not listen on external interfaces");
        } finally {
            server.stop();
        }
    }
}
