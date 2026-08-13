package io.agentforge.agent;

import java.util.Locale;

public enum CodingAgentType {
    CODEX;

    public static CodingAgentType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Coding agent type is required");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown coding agent type: " + value, exception);
        }
    }
}
