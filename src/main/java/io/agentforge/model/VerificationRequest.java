package io.agentforge.model;

import java.nio.file.Path;

public record VerificationRequest(Path path, String task) {
    public VerificationRequest {
        if (path == null) throw new IllegalArgumentException("Verification path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Verification task is required");
    }
}
