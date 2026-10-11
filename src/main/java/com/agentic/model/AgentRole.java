package com.agentic.model;

/** Roles an agent can play. HUMAN marks approval nodes; NONE marks pure graph nodes (barriers, final). */
public enum AgentRole { REQUIREMENTS, ARCHITECT, IMPLEMENTER, TESTER, SECURITY, DOCS, RELEASE, HUMAN, NONE }
