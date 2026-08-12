package io.agentforge.model;

public record PlanTest(String description) {
    public PlanTest {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Plan test description is required");
        }
    }
}
