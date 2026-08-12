package io.agentforge.model;

import java.util.List;

public record AuditReport(
        RepositoryReport repositoryReport,
        List<ScoreBreakdown> readinessScores,
        int overallScore,
        double testFileRatioThreshold,
        RecommendationReport recommendationReport,
        List<String> warnings
) {
    public AuditReport {
        readinessScores = List.copyOf(readinessScores);
        if (recommendationReport == null) throw new IllegalArgumentException("Recommendation report is required");
        warnings = List.copyOf(warnings);
    }
}
