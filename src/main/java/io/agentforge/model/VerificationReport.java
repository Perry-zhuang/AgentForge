package io.agentforge.model;

import java.util.List;
import java.util.Optional;

public record VerificationReport(
        EngineeringPlan plan,
        EngineeringBoundarySnapshot executionBoundary,
        DiffAuditReport diffAudit,
        Baseline baseline,
        VerificationStatus scopeStatus,
        VerificationStatus policyStatus,
        VerificationStatus taskTypeStatus,
        VerificationStatus architectureStatus,
        VerificationStatus overallStatus,
        List<VerificationFinding> findings,
        List<String> limitations,
        VerificationChangeSet changeSet,
        Optional<IntentConsistencyReport> intentConsistency
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
        this(plan, null, diffAudit, baseline, scopeStatus, policyStatus, taskTypeStatus, architectureStatus,
                overallStatus, findings, limitations, VerificationChangeSet.currentWorkingTree(diffAudit),
                Optional.empty());
    }

    public VerificationReport(EngineeringPlan plan, EngineeringBoundarySnapshot executionBoundary,
                              DiffAuditReport diffAudit, Baseline baseline, VerificationStatus scopeStatus,
                              VerificationStatus policyStatus, VerificationStatus taskTypeStatus,
                              VerificationStatus architectureStatus, VerificationStatus overallStatus,
                              List<VerificationFinding> findings, List<String> limitations,
                              VerificationChangeSet changeSet) {
        this(plan, executionBoundary, diffAudit, baseline, scopeStatus, policyStatus, taskTypeStatus,
                architectureStatus, overallStatus, findings, limitations, changeSet, Optional.empty());
    }

    public VerificationReport {
        if (baseline == null || scopeStatus == null
                || policyStatus == null || taskTypeStatus == null || architectureStatus == null
                || overallStatus == null || changeSet == null || intentConsistency == null) {
            throw new IllegalArgumentException("Verification report fields are required");
        }
        if (baseline != changeSet.baseline()) {
            throw new IllegalArgumentException("Verification report baseline and change facts must agree");
        }
        if (baseline == Baseline.CURRENT_WORKING_TREE && diffAudit == null) {
            throw new IllegalArgumentException("Current-working-tree report requires Diff audit facts");
        }
        if (baseline == Baseline.CURRENT_WORKING_TREE && (plan == null || executionBoundary != null)) {
            throw new IllegalArgumentException("Current-working-tree report requires a current EngineeringPlan only");
        }
        if (baseline == Baseline.EXECUTION_DELTA && diffAudit != null) {
            throw new IllegalArgumentException("Execution report cannot contain whole-working-tree Diff facts");
        }
        if (baseline == Baseline.EXECUTION_DELTA && (executionBoundary == null || plan != null)) {
            throw new IllegalArgumentException("Execution report requires a baseline engineering boundary only");
        }
        findings = List.copyOf(findings);
        limitations = List.copyOf(limitations);
    }

    public VerificationReport withIntentConsistency(IntentConsistencyReport consistency) {
        VerificationStatus combined = overallStatus == VerificationStatus.FAIL
                || consistency.status() == VerificationStatus.FAIL ? VerificationStatus.FAIL : overallStatus;
        return new VerificationReport(plan, executionBoundary, diffAudit, baseline, scopeStatus, policyStatus,
                taskTypeStatus, architectureStatus, combined, findings, limitations, changeSet,
                Optional.of(consistency));
    }

    public enum Baseline {
        CURRENT_WORKING_TREE,
        EXECUTION_DELTA
    }
}
