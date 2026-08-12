package io.agentforge.model;

public record MatchEvidence(String signal, int points, String detail) {
    public MatchEvidence {
        if (signal == null || signal.isBlank()) throw new IllegalArgumentException("Evidence signal is required");
        if (points < 0) throw new IllegalArgumentException("Evidence points cannot be negative");
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("Evidence detail is required");
    }
}
