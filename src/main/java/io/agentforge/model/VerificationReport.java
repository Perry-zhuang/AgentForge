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
        List<String> limitations,
        VerificationChangeSet changeSet
) {
    public VerificationReport(
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
        this(plan, diffAudit, baseline, scopeStatus, policyStatus, taskTypeStatus, architectureStatus,
                overallStatus, findings, limitations, VerificationChangeSet.currentWorkingTree(diffAudit));
    }

    public VerificationReport {
        if (plan == null || baseline == null || scopeStatus == null
                || policyStatus == null || taskTypeStatus == null || architectureStatus == null
                || overallStatus == null || changeSet == null) {
            throw new IllegalArgumentException("Verification report fields are required");
        }
        if (baseline != changeSet.baseline()) {
            throw new IllegalArgumentException("Verification report baseline and change facts must agree");
        }
        if (baseline == Baseline.CURRENT_WORKING_TREE && diffAudit == null) {
            throw new IllegalArgumentException("Current-working-tree report requires Diff audit facts");
        }
        if (baseline == Baseline.EXECUTION_DELTA && diffAudit != null) {
            throw new IllegalArgumentException("Execution report cannot contain whole-working-tree Diff facts");
        }
        findings = List.copyOf(findings);
        limitations = List.copyOf(limitations);
    }

    public enum Baseline {
        CURRENT_WORKING_TREE,
        EXECUTION_DELTA
    }
}
