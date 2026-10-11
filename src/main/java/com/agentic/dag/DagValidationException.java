package com.agentic.dag;

import java.util.List;

public final class DagValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<String> problems;

    public DagValidationException(List<String> problems) {
        super("Invalid DAG: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }
}
