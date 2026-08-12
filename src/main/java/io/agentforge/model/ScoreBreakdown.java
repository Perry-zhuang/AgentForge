package io.agentforge.model;

import java.util.List;

public record ScoreBreakdown(String name, int score, List<String> details) {
    public ScoreBreakdown {
        details = List.copyOf(details);
    }
}
