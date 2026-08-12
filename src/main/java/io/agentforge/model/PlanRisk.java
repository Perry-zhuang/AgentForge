package io.agentforge.model;

import java.util.List;

public record PlanRisk(int score, Level level, List<Factor> factors) {
    public PlanRisk {
        if (score < 0) throw new IllegalArgumentException("Plan risk score cannot be negative");
        if (level == null) throw new IllegalArgumentException("Plan risk level is required");
        factors = List.copyOf(factors);
    }

    public enum Level { LOW, MEDIUM, HIGH }

    public record Factor(int points, String reason) {
        public Factor {
            if (points < 1) throw new IllegalArgumentException("Plan risk factor points must be positive");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Plan risk factor reason is required");
            }
        }
    }
}
