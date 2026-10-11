package com.agentic.policy;

import com.agentic.model.ArtifactDraft;

import java.util.List;
import java.util.regex.Pattern;

/** SEC-001: no secrets in any agent output. Runs before anything is written to disk. */
public final class SecretsRule implements PolicyRule {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|token)\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_\\-]{8,}"));

    @Override public String id() { return "SEC-001"; }

    @Override public boolean applies(PolicyContext ctx) { return ctx.phase() == PolicyPhase.POST_ACTION; }

    @Override
    public PolicyVerdict evaluate(PolicyContext ctx) {
        for (ArtifactDraft d : ctx.outputs()) {
            for (Pattern p : PATTERNS) {
                if (p.matcher(d.content()).find()) {
                    return PolicyVerdict.deny(id(), "possible secret in " + d.type() + " (pattern " + p.pattern().substring(0, Math.min(20, p.pattern().length())) + "...)");
                }
            }
        }
        return PolicyVerdict.allow(id(), "no secret patterns found");
    }
}
