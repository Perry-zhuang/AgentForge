package io.agentforge.model;

public record PlanConstraint(Kind kind, String description) {
    public PlanConstraint {
        if (kind == null) throw new IllegalArgumentException("Plan constraint kind is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Plan constraint description is required");
        }
    }

    public enum Kind {
        POLICY_DENY,
        POLICY_PROTECTED,
        ARCHITECTURE_LAYER,
        ARCHITECTURE_ALLOWED,
        ARCHITECTURE_UNDECLARED,
        OBSERVED_FLOW
    }
}
