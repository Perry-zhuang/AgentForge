package io.agentforge.model;

import java.util.List;

public record PolicyReport(List<PolicyViolation> violations) {
    public PolicyReport {
        violations = List.copyOf(violations);
    }

    public boolean failed() {
        return violations.stream().anyMatch(v -> v.severity() == ViolationSeverity.ERROR);
    }
}
