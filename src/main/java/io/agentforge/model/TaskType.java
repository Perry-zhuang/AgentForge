package io.agentforge.model;

public enum TaskType {
    BUGFIX,
    FEATURE,
    REFACTOR,
    INTEGRATION,
    ANALYSIS,
    UNKNOWN;

    public static TaskType parseOverride(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            TaskType type = valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            if (type == UNKNOWN) throw new IllegalArgumentException("--task-type does not allow unknown");
            return type;
        } catch (IllegalArgumentException exception) {
            if ("--task-type does not allow unknown".equals(exception.getMessage())) throw exception;
            throw new IllegalArgumentException("Unknown --task-type value: " + value
                    + " (expected: analysis, bugfix, feature, integration, or refactor)");
        }
    }
}
