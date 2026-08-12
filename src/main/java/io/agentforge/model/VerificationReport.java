package io.agentforge.model;

import java.util.List;

public record VerificationReport(
        EngineeringPlan plan,
        DiffAuditReport diffAudit,
        Baseline baseline,
        VerificationStatus scopeStatus,
        VerificationStatus policyStatus,
        VerificationStatus taskTypeStatus,
        VerificationStatus architectureStatus,
        VerificationStatus overallStatus,
        List<VerificationFinding> findings,
        List<String> limitations
) {
    public VerificationReport {
        if (plan == null || diffAudit == null || baseline == null || scopeStatus == null
                || policyStatus == null || taskTypeStatus == null || architectureStatus == null
                || overallStatus == null) {
            throw new IllegalArgumentException("Verification report fields are required");
        }
        findings = List.copyOf(findings);
        limitations = List.copyOf(limitations);
    }

    public enum Baseline {
        CURRENT_WORKING_TREE
    }
}
