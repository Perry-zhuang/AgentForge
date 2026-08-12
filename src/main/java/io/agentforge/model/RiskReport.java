package io.agentforge.model;

import java.util.List;

public record RiskReport(int score, RiskLevel level, List<RiskFactor> factors) {
    public RiskReport {
        factors = List.copyOf(factors);
    }
}
