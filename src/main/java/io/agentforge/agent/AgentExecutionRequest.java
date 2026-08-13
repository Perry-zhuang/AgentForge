package io.agentforge.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable process-driving facts prepared before an adapter is invoked. */
public record AgentExecutionRequest(
        CodingAgentIdentity agent,
        Path repositoryRoot,
        String renderedPrompt,
        Duration timeout,
        Map<String, String> environmentOverrides
) {
    public AgentExecutionRequest {
        if (agent == null) throw new IllegalArgumentException("Coding agent identity is required");
        repositoryRoot = canonicalDirectory(repositoryRoot);
        if (renderedPrompt == null || renderedPrompt.isBlank()) {
            throw new IllegalArgumentException("Rendered prompt is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
            throw new IllegalArgumentException("Agent execution timeout must be at least one millisecond");
        }
        environmentOverrides = immutableEnvironment(environmentOverrides);
    }

    private static Path canonicalDirectory(Path directory) {
        if (directory == null) throw new IllegalArgumentException("Repository root is required");
        try {
            Path canonical = directory.toRealPath();
            if (!Files.isDirectory(canonical)) {
                throw new IllegalArgumentException("Repository root is not a directory: " + canonical);
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to resolve repository root: " + directory, exception);
        }
    }

    private static Map<String, String> immutableEnvironment(Map<String, String> overrides) {
        if (overrides == null) throw new IllegalArgumentException("Environment overrides are required");
        TreeMap<String, String> copy = new TreeMap<>();
        overrides.forEach((name, value) -> {
            if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid environment override name: " + name);
            }
            if (value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid environment override value for: " + name);
            }
            copy.put(name, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
