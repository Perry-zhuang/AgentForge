package io.agentforge.model;

public record ConfidenceProfile(RelevanceLevel level, String reason) {
    public ConfidenceProfile {
        if (level == null) throw new IllegalArgumentException("Confidence level is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Confidence reason is required");
    }
}
