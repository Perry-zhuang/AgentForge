package io.agentforge.model;

import java.nio.file.Path;

public record EngineeringPlanRequest(Path path, String task) {
    public EngineeringPlanRequest {
        if (path == null) throw new IllegalArgumentException("Plan path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Plan task must not be blank");
        task = task.strip();
    }
}
