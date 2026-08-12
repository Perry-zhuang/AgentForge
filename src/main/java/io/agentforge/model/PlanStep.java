package io.agentforge.model;

public record PlanStep(int order, String description) {
    public PlanStep {
        if (order < 1) throw new IllegalArgumentException("Plan step order must be positive");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Plan step description is required");
        }
    }
}
