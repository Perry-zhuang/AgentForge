package io.agentforge.model;

import java.util.List;
import java.util.Objects;

public record Recommendation(
        String id,
        RecommendationCategory category,
        RecommendationPriority priority,
        String title,
        String reason,
        String action,
        int estimatedImpact,
        List<String> evidence
) {
    public Recommendation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(action, "action");
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) throw new IllegalArgumentException("Recommendation evidence must not be empty");
        if (estimatedImpact < 0) throw new IllegalArgumentException("Estimated impact must not be negative");
    }
}
