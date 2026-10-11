package com.agentic.gate;

import java.util.List;
import java.util.stream.Collectors;

public record GateReport(boolean passed, List<CheckResult> results) {

    public String failureSummary() {
        return results.stream().filter(r -> !r.passed())
                .map(r -> r.name() + " (" + r.detail() + ")")
                .collect(Collectors.joining("; "));
    }
}
