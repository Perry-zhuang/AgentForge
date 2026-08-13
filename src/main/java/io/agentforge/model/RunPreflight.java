package io.agentforge.model;

public record RunPreflight(Status status, Reason reason, String warning) {
    public RunPreflight {
        if (status == null || reason == null || warning == null) {
            throw new IllegalArgumentException("Run preflight facts are required");
        }
    }

    public boolean allowed() {
        return status != Status.BLOCKED;
    }

    public enum Status { ALLOWED, ALLOWED_WITH_WARNING, BLOCKED }

    public enum Reason {
        CLASSIFICATION_RESOLVED,
        UNKNOWN_READ_ONLY,
        TASK_TYPE_UNRESOLVED,
        UNKNOWN_HIGH_SECURITY,
        TASK_TYPE_OVERRIDE_CONFLICTS_WITH_EXPLICIT_READ_ONLY_INTENT
    }
}
