package io.agentforge.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Versioned task-aware baseline used by execution verification. */
@JsonPropertyOrder({"schemaVersion", "repositorySnapshot", "engineeringBoundary"})
public record ExecutionBaseline(
        int schemaVersion,
        RepositorySnapshot repositorySnapshot,
        EngineeringBoundarySnapshot engineeringBoundary
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ExecutionBaseline {
        if (repositorySnapshot == null || engineeringBoundary == null) {
            throw new IllegalArgumentException("Execution baseline repository and boundary are required");
        }
        if (repositorySnapshot.schemaVersion() != RepositorySnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported repository snapshot schema version: "
                    + repositorySnapshot.schemaVersion());
        }
    }
}
