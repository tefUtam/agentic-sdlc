package com.agentic.model;

/** Every artifact type has exactly one producing node in a graph (enforced by Dag.validate). */
public enum ArtifactType {
    AMBIGUITY_REPORT("md"),
    REQUIREMENT_SPEC("md"),
    ARCH_DOC("md"),
    OPENAPI_SPEC("yaml"),
    DB_SCHEMA("sql"),
    CODE_CORE("java.txt"),
    CODE_ANALYTICS("java.txt"),
    UNIT_TEST_REPORT("txt"),
    INTEGRATION_TEST_REPORT("txt"),
    SEC_REPORT("txt"),
    DOCS_BUNDLE("md"),
    RELEASE_REPORT("md");

    private final String extension;

    ArtifactType(String extension) { this.extension = extension; }

    public String extension() { return extension; }
}
