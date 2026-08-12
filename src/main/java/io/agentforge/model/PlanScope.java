package io.agentforge.model;

import java.util.List;

public record PlanScope(
        List<RelevantFile> primary,
        List<RelevantFile> secondary,
        List<RelevantFile> reference
) {
    public PlanScope {
        primary = List.copyOf(primary);
        secondary = List.copyOf(secondary);
        reference = List.copyOf(reference);
    }
}
