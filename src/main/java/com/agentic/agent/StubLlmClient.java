package com.agentic.agent;

import com.agentic.model.ArtifactType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a model: canned but structurally realistic artifacts, so the orchestrator,
 * gates and policies can be exercised reproducibly. Its outputs say nothing about real model quality.
 */
public final class StubLlmClient implements LlmClient {
    private static final Pattern VAGUE = Pattern.compile(
            "\\b(smart\\w*|better|modern|secur\\w*|fast\\w*|intuitive|robust|nice|improv\\w*|easy|easier|etc)\\b");

    private final long latencyMs;

    public StubLlmClient(long latencyMs) { this.latencyMs = latencyMs; }

    @Override
    public LlmResponse complete(LlmRequest r) throws InterruptedException {
        if (latencyMs > 0) Thread.sleep(latencyMs);
        String requirement = r.context().getOrDefault("requirement", "(not provided)");
        return switch (r.target()) {
            case AMBIGUITY_REPORT -> ambiguity(requirement);
            case REQUIREMENT_SPEC -> new LlmResponse("""
                    # Requirement Specification
                    ## Requirements
                    - R1: POST /api/v1/links creates a short link for a valid http(s) URL.
                    - R2: GET /{code} responds with a redirect to the original URL.
                    - R3: Every redirect records a click event (timestamp, referrer, hashed visitor id).
                    - R4: GET /api/v1/links/{code}/stats returns click counts.
                    - R5: Reliability: per-client rate limiting, GET /healthz, bounded storage retries.
                    ## Assumptions
                    - A1: Single region, single database. Custom aliases and expiry are out of scope for v1.
                    - A2: Raw IP addresses and user agents are never stored.
                    ## Out of scope
                    - Authentication, billing, multi-tenant isolation.
                    """, "Normalised the request into five testable requirements and logged assumptions explicitly.");
            case ARCH_DOC -> new LlmResponse("""
                    # Architecture
                    ## Components
                    - API layer (HTTP handlers), Link service, Click recorder, Rate limiter, Storage adapter.
                    ## Key decisions
                    - 302 (not 301) redirects so every click reaches the server and can be counted.
                    - Short codes: 7 chars base62 from a CSPRNG, unique-constraint retry on collision.
                    """, "Chose 302 over 301 so analytics stay accurate; browsers cache 301 and would hide repeat clicks.");
            case OPENAPI_SPEC -> new LlmResponse("""
                    openapi: 3.0.3
                    info:
                      title: URL Shortener API
                      version: 1.0.0
                    paths:
                      /api/v1/links:
                        post:
                          summary: Create a short link
                          responses:
                            '201': {description: Created}
                            '400': {description: Invalid URL}
                            '429': {description: Rate limited}
                      /{code}:
                        get:
                          summary: Redirect to the original URL
                          responses:
                            '302': {description: Found}
                            '404': {description: Unknown code}
                      /api/v1/links/{code}/stats:
                        get:
                          summary: Click statistics
                          responses:
                            '200': {description: OK}
                      /healthz:
                        get:
                          summary: Liveness probe
                          responses:
                            '200': {description: OK}
                    """, "Versioned the API under /api/v1 and kept the redirect route at the root for short URLs.");
            case DB_SCHEMA -> new LlmResponse("""
                    CREATE TABLE links (
                      code        TEXT PRIMARY KEY,
                      target_url  TEXT NOT NULL,
                      created_at  TIMESTAMP NOT NULL
                    );
                    CREATE TABLE clicks (
                      id          INTEGER PRIMARY KEY,
                      code        TEXT NOT NULL REFERENCES links(code),
                      clicked_at  TIMESTAMP NOT NULL,
                      referrer    TEXT,
                      visitor_hash TEXT NOT NULL
                    );
                    CREATE INDEX idx_clicks_code ON clicks(code);
                    """, "Store a salted visitor hash rather than IP or user agent to avoid holding personal data.");
            case CODE_CORE -> new LlmResponse("""
                    package com.shortener.core;

                    public final class ShortCodeGenerator {
                        private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
                        private final java.security.SecureRandom random = new java.security.SecureRandom();

                        public String next() {
                            StringBuilder sb = new StringBuilder(7);
                            for (int i = 0; i < 7; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                            return sb.toString();
                        }
                    }
                    """, "Used SecureRandom so codes cannot be enumerated by predicting a sequence.");
            case CODE_ANALYTICS -> new LlmResponse("""
                    package com.shortener.analytics;

                    public final class ClickRecorder {
                        public record Click(String code, java.time.Instant at, String referrer, String visitorHash) {}
                    }
                    """, "Analytics records are write-only value objects, which keeps the redirect hot path simple.");
            case UNIT_TEST_REPORT -> new LlmResponse("""
                    Unit tests: passed: 42, failed: 0, skipped: 0
                    Line coverage: 87%
                    """, "");
            case INTEGRATION_TEST_REPORT -> new LlmResponse("""
                    Integration tests: passed: 12, failed: 0, skipped: 0
                    """, "");
            case SEC_REPORT -> new LlmResponse("""
                    Static analysis and dependency scan
                    Findings - high: 0, medium: 1, low: 2
                    """, "");
            case DOCS_BUNDLE -> new LlmResponse("""
                    # URL Shortener
                    ## Getting started
                    Build, run, and call the API (see OpenAPI spec).
                    ## Operations
                    Health endpoint, rate-limit behaviour, and data-retention notes.
                    """, "");
            case RELEASE_REPORT -> new LlmResponse("""
                    # Release Readiness Report
                    Verdict: READY
                    - Unit and integration tests green; coverage above threshold.
                    - Security scan: no high findings; one medium accepted with rationale.
                    - Documentation bundle present.
                    """, "Verdict follows from the verification artifacts; final release still needs human approval.");
        };
    }

    private static LlmResponse ambiguity(String requirement) {
        String lower = requirement.toLowerCase(Locale.ROOT);
        int words = requirement.trim().isEmpty() ? 0 : requirement.trim().split("\\s+").length;
        Matcher m = VAGUE.matcher(lower);
        int vague = 0;
        StringBuilder questions = new StringBuilder();
        while (m.find()) {
            vague++;
            questions.append("- What does '").append(m.group()).append("' mean in measurable terms?\n");
        }
        double score = 0.15 + 0.35 * vague + (words < 6 ? 0.4 : 0.0);
        score = Math.min(score, 0.95);
        if (questions.length() == 0) questions.append("- Should custom aliases or link expiry be in scope for v1?\n");
        String content = "# Ambiguity Report\nRequirement: " + requirement + "\n"
                + String.format(Locale.ROOT, "Ambiguity score: %.2f%n", score)
                + "Open questions:\n" + questions;
        return new LlmResponse(content, "Scored ambiguity from vague, unmeasurable terms and very short requests.");
    }
}
