package com.agentic.approval;

import com.agentic.store.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Small approval API on the JDK's built-in HTTP server. Loopback only; no authentication (see limitations).
 *   GET  /approvals                       pending approvals
 *   POST /approvals/{id}/decision         form body: decision=APPROVE|REJECT, approver=..., rationale=..., artifactHash=...
 *   GET  /status                          run and node states
 */
public final class ApprovalServer {
    private final HttpServer server;
    private final ApprovalManager approvals;
    private final Supplier<Map<String, Object>> status;

    public ApprovalServer(ApprovalManager approvals, Supplier<Map<String, Object>> status, int port) throws IOException {
        this.approvals = approvals;
        this.status = status;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/approvals", this::handleApprovals);
        server.createContext("/status", ex -> {
            try {
                if (!ex.getRequestMethod().equals("GET")) send(ex, 405, error("method not allowed"));
                else send(ex, 200, Json.write(this.status.get()));
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "approval-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    private void handleApprovals(HttpExchange ex) throws IOException {
        try {
            String[] parts = ex.getRequestURI().getPath().split("/"); // "", "approvals", [id, "decision"]
            String method = ex.getRequestMethod();
            if (parts.length == 2 && method.equals("GET")) {
                send(ex, 200, Json.write(approvals.pending().stream().map(ApprovalView::toMap).toList()));
            } else if (parts.length == 4 && parts[3].equals("decision") && method.equals("POST")) {
                decide(ex, parts[2]);
            } else {
                send(ex, 404, error("not found"));
            }
        } catch (RuntimeException e) {
            send(ex, 500, error("internal error"));
        } finally {
            ex.close();
        }
    }

    private void decide(HttpExchange ex, String id) throws IOException {
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        ApprovalStatus st = switch (form.getOrDefault("decision", "").toUpperCase(Locale.ROOT)) {
            case "APPROVE", "APPROVED" -> ApprovalStatus.APPROVED;
            case "REJECT", "REJECTED" -> ApprovalStatus.REJECTED;
            case "REQUEST_CHANGES" -> ApprovalStatus.CHANGES_REQUESTED;
            default -> null;
        };
        if (st == null) {
            send(ex, 400, error("decision must be APPROVE, REJECT or REQUEST_CHANGES"));
            return;
        }
        try {
            ApprovalView v = approvals.decide(id, st, form.get("approver"), form.getOrDefault("rationale", ""), form.get("artifactHash"));
            send(ex, 200, Json.write(v.toMap()));
        } catch (NoSuchElementException e) {
            send(ex, 404, error(e.getMessage()));
        } catch (IllegalStateException e) {
            send(ex, 409, error(e.getMessage()));
        } catch (UnsupportedOperationException e) {
            send(ex, 501, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        }
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return m;
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String error(String message) { return Json.write(Json.obj("error", message)); }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
