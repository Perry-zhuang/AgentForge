package io.agentforge.model;

public record EstimatedChangeScope(
        int minimumFiles,
        int maximumFiles,
        int modules,
        boolean databaseChange,
        boolean dependencyChange,
        boolean securityChange,
        boolean configChange
) {
    public EstimatedChangeScope {
        if (minimumFiles < 0 || maximumFiles < minimumFiles) {
            throw new IllegalArgumentException("Estimated file range is invalid");
        }
        if (modules < 0) throw new IllegalArgumentException("Estimated module count cannot be negative");
    }
}
