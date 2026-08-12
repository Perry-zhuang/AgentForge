package io.agentforge.model;

import java.nio.file.Path;

public record EngineeringPromptRequest(Path path, String task) {
    public EngineeringPromptRequest {
        if (path == null) throw new IllegalArgumentException("Prompt path is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Prompt task must not be blank");
        task = task.strip();
    }
}
