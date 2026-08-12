package io.agentforge.model;

import java.util.List;

public record AuditReport(
        RepositoryReport repositoryReport,
        List<ScoreBreakdown> readinessScores,
        int overallScore,
        List<String> warnings
) {
    public AuditReport {
        readinessScores = List.copyOf(readinessScores);
        warnings = List.copyOf(warnings);
    }
}
