package io.agentforge.model;

import java.util.List;

public record PlanScopeOverlap(
        int changedRelevantFiles,
        List<String> overlappingPaths,
        boolean primaryOverlap,
        boolean secondaryOverlap
) {
    public PlanScopeOverlap {
        if (changedRelevantFiles < 0) {
            throw new IllegalArgumentException("Changed relevant file count cannot be negative");
        }
        overlappingPaths = List.copyOf(overlappingPaths);
    }

    public static PlanScopeOverlap none() {
        return new PlanScopeOverlap(0, List.of(), false, false);
    }
}
