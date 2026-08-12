package io.agentforge.model;

import java.util.List;

public record RecommendationReport(List<Recommendation> recommendations) {
    public RecommendationReport {
        recommendations = List.copyOf(recommendations);
    }
}
