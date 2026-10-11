package com.agentic.gate;

public record GateResult(boolean passed, String detail) {
    public static GateResult ok(String detail) { return new GateResult(true, detail); }
    public static GateResult fail(String detail) { return new GateResult(false, detail); }
}
