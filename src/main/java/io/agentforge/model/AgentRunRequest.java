package io.agentforge.model;

import io.agentforge.agent.CodingAgentType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record AgentRunRequest(
        Path repositoryRoot,
        String task,
        CodingAgentType agentType,
        Duration timeout,
        Map<String, String> environmentOverrides,
        Path outputFile,
        TaskType taskTypeOverride
) {
    public AgentRunRequest(Path repositoryRoot, String task, CodingAgentType agentType, Duration timeout,
                           Map<String, String> environmentOverrides) {
        this(repositoryRoot, task, agentType, timeout, environmentOverrides, null, null);
    }

    public AgentRunRequest(Path repositoryRoot, String task, CodingAgentType agentType, Duration timeout,
                           Map<String, String> environmentOverrides, Path outputFile) {
        this(repositoryRoot, task, agentType, timeout, environmentOverrides, outputFile, null);
    }

    public AgentRunRequest {
        if (repositoryRoot == null) throw new IllegalArgumentException("Agent run repository root is required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Agent run task is required");
        if (agentType == null) throw new IllegalArgumentException("Agent run coding agent type is required");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
            throw new IllegalArgumentException("Agent run timeout must be at least one millisecond");
        }
        if (environmentOverrides == null) {
            throw new IllegalArgumentException("Agent run environment overrides are required");
        }
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        TreeMap<String, String> environment = new TreeMap<>();
        environment.putAll(environmentOverrides);
        environmentOverrides = Collections.unmodifiableMap(environment);
        outputFile = outputFile == null ? null : outputFile.toAbsolutePath().normalize();
        if (taskTypeOverride == TaskType.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN cannot be used as a task-type override");
        }
    }
}
