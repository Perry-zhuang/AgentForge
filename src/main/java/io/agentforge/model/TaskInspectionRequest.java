package io.agentforge.model;

import java.nio.file.Path;

public record TaskInspectionRequest(Path path, String task, int limit) {
    public TaskInspectionRequest {
        if (path == null) throw new IllegalArgumentException("Inspection path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Inspection task must not be blank");
        if (limit < 1 || limit > 15) throw new IllegalArgumentException("Inspection limit must be between 1 and 15");
        task = task.strip();
    }
}
