package io.agentforge.model;

public record VerificationFinding(
        Category category,
        ViolationSeverity severity,
        String code,
        String path,
        String message
) {
    public VerificationFinding {
        if (category == null || severity == null) {
            throw new IllegalArgumentException("Verification finding category and severity are required");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Verification finding code is required");
        path = path == null || path.isBlank() ? "-" : path;
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Verification finding message is required");
        }
    }

    public enum Category {
        SCOPE,
        POLICY,
        TASK_TYPE,
        ARCHITECTURE
    }
}
