package com.agentic.gate;

import java.util.ArrayList;
import java.util.List;

/** Runs every check (no short-circuit, so the audit trail shows all failures at once). */
public final class Gate {
    private Gate() {}

    public static GateReport evaluate(List<NamedCheck> checks, GateContext ctx) {
        List<CheckResult> results = new ArrayList<>();
        boolean all = true;
        for (NamedCheck c : checks) {
            GateResult r;
            try {
                r = c.check().evaluate(ctx);
            } catch (RuntimeException e) {
                r = GateResult.fail("check threw " + e);
            }
            results.add(new CheckResult(c.name(), r.passed(), r.detail()));
            all &= r.passed();
        }
        return new GateReport(all, List.copyOf(results));
    }
}
