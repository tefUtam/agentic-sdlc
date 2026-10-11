package com.agentic.policy;

/** Ordered by severity: the engine returns the most severe outcome among applicable rules. */
public enum PolicyOutcome { ALLOW, REQUIRE_APPROVAL, DENY }
