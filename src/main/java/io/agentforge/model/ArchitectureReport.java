package io.agentforge.model;

import java.util.List;
import java.util.Map;

public record ArchitectureReport(
        DependencyGraph graph,
        Map<JavaRole, Integer> roleCounts,
        Map<String, Integer> relationCounts,
        List<DependencyEdge> possibleLayerBypasses,
        List<DependencyEdge> contractViolations,
        boolean contractConfigured,
        ArchitectureAnalysisMode analysisMode,
        List<String> warnings
) {
    public ArchitectureReport {
        roleCounts = Map.copyOf(roleCounts);
        relationCounts = Map.copyOf(relationCounts);
        possibleLayerBypasses = List.copyOf(possibleLayerBypasses);
        contractViolations = List.copyOf(contractViolations);
        if (analysisMode == null) throw new IllegalArgumentException("Architecture analysis mode is required");
        warnings = List.copyOf(warnings);
    }
}
