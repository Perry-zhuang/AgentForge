package io.agentforge.model;

import java.nio.file.Path;

public record VerificationRequest(Path path, String task, Path baselineFile) {
    public VerificationRequest(Path path, String task) {
        this(path, task, null);
    }

    public VerificationRequest {
        if (path == null) throw new IllegalArgumentException("Verification path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Verification task is required");
    }

    public boolean executionDeltaMode() {
        return baselineFile != null;
    }
}
