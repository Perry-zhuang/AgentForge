package io.agentforge.model;

public record AcceptanceCriterion(String description) {
    public AcceptanceCriterion {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Acceptance criterion description is required");
        }
    }
}
