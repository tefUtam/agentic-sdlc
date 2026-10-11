package com.agentic.dag;

/** AGENT runs an agent; APPROVAL blocks on a human; SYNC is a join barrier; FINAL is the single sink. */
public enum NodeKind { AGENT, APPROVAL, SYNC, FINAL }
