package io.agentforge.model;

import java.util.List;

public record TaskClassification(
        TaskType detectedType,
        TaskType effectiveType,
        TaskTypeConfidence typeConfidence,
        MutationMode mutationMode,
        SecurityImpact securityImpact,
        ClassificationSource source,
        List<ClassificationSignal> signals,
        boolean explicitReadOnly
) {
    public TaskClassification {
        if (detectedType == null || effectiveType == null || typeConfidence == null || mutationMode == null
                || securityImpact == null || source == null || signals == null) {
            throw new IllegalArgumentException("Task classification facts are required");
        }
        signals = List.copyOf(signals);
        if (source == ClassificationSource.DETECTED && detectedType != effectiveType) {
            throw new IllegalArgumentException("Detected classification must preserve its effective type");
        }
        if (source == ClassificationSource.USER_OVERRIDE && effectiveType == TaskType.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN cannot be used as a task-type override");
        }
    }

    public static TaskClassification legacy(TaskType type, String signal) {
        ClassificationSignal evidence = switch (type) {
            case ANALYSIS -> ClassificationSignal.ANALYSIS;
            case BUGFIX -> ClassificationSignal.BUGFIX;
            case FEATURE -> ClassificationSignal.FEATURE;
            case INTEGRATION -> ClassificationSignal.INTEGRATION;
            case REFACTOR -> ClassificationSignal.REFACTOR;
            case UNKNOWN -> null;
        };
        MutationMode mode = type == TaskType.ANALYSIS ? MutationMode.READ_ONLY
                : type == TaskType.UNKNOWN ? MutationMode.UNKNOWN : MutationMode.SOURCE_WRITE;
        return new TaskClassification(type, type,
                type == TaskType.UNKNOWN ? TaskTypeConfidence.LOW : TaskTypeConfidence.HIGH,
                mode, SecurityImpact.NONE, ClassificationSource.DETECTED,
                evidence == null ? List.of() : List.of(evidence), false);
    }

    public TaskClassification withOverride(TaskType override) {
        if (override == null) return this;
        if (override == TaskType.UNKNOWN) throw new IllegalArgumentException("UNKNOWN cannot be used as a task-type override");
        MutationMode overriddenMode = explicitReadOnly ? MutationMode.READ_ONLY
                : override == TaskType.ANALYSIS ? MutationMode.READ_ONLY : MutationMode.SOURCE_WRITE;
        return new TaskClassification(detectedType, override, TaskTypeConfidence.HIGH, overriddenMode,
                securityImpact, ClassificationSource.USER_OVERRIDE, signals, explicitReadOnly);
    }
}
