package com.agentic.dag;

/** HARD and SYNC require the predecessor to succeed; SOFT is advisory only. */
public enum EdgeKind {
    HARD, SOFT, SYNC;

    public boolean requiresSuccess() { return this != SOFT; }
}
