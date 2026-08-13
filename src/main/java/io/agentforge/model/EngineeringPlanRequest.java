package io.agentforge.model;

import java.nio.file.Path;

public record EngineeringPlanRequest(Path path, String task, TaskType taskTypeOverride) {
    public EngineeringPlanRequest(Path path, String task) {
        this(path, task, null);
    }

    public EngineeringPlanRequest {
        if (path == null) throw new IllegalArgumentException("Plan path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Plan task must not be blank");
        task = task.strip();
        if (taskTypeOverride == TaskType.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN cannot be used as a task-type override");
        }
    }
}
