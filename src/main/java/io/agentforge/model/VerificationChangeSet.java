package io.agentforge.model;

import java.util.List;
import java.util.Optional;

/**
 * Normalized change facts consumed by boundary verification.
 *
 * <p>This model deliberately contains no compliance decisions. It only preserves the facts supplied by
 * the current-working-tree audit or an execution delta.</p>
 */
public record VerificationChangeSet(
        VerificationReport.Baseline baseline,
        RepositoryInfo repository,
        List<VerificationChange> changes,
        PolicyReport policyReport,
        ArchitectureReport architectureReport,
        boolean defaultPolicyUsed,
        Optional<DiffAuditReport> currentWorkingTreeAudit,
        Optional<ExecutionChangeReport> executionChangeReport,
        List<String> limitations
) {
    public VerificationChangeSet {
        if (baseline == null || repository == null || changes == null || policyReport == null
                || architectureReport == null || currentWorkingTreeAudit == null
                || executionChangeReport == null || limitations == null) {
            throw new IllegalArgumentException("Verification change facts are required");
        }
        changes = List.copyOf(changes);
        limitations = List.copyOf(limitations);
        if (baseline == VerificationReport.Baseline.CURRENT_WORKING_TREE) {
            if (currentWorkingTreeAudit.isEmpty() || executionChangeReport.isPresent()) {
                throw new IllegalArgumentException("Current-working-tree verification requires Diff facts only");
            }
        } else if (currentWorkingTreeAudit.isPresent() || executionChangeReport.isEmpty()) {
            throw new IllegalArgumentException("Execution verification requires execution-delta facts only");
        }
    }

    public static VerificationChangeSet currentWorkingTree(DiffAuditReport audit) {
        if (audit == null) throw new IllegalArgumentException("Diff audit facts are required");
        List<VerificationChange> changes = audit.changeReport().files().stream()
                .map(file -> new VerificationChange(kind(file.status()), file.path(), file.oldPath(), true))
                .toList();
        return new VerificationChangeSet(VerificationReport.Baseline.CURRENT_WORKING_TREE,
                audit.changeReport().repository(), changes, audit.policyReport(), audit.architectureReport(),
                audit.defaultPolicyUsed(), Optional.of(audit), Optional.empty(), List.of());
    }

    public static VerificationChangeSet executionDelta(
            RepositoryInfo repository,
            ExecutionChangeReport execution,
            PolicyReport policyReport,
            ArchitectureReport architectureReport,
            boolean defaultPolicyUsed,
            List<String> limitations
    ) {
        if (execution == null) throw new IllegalArgumentException("Execution delta facts are required");
        List<VerificationChange> changes = execution.changes().stream()
                .map(change -> new VerificationChange(kind(change.type()), change.path(), change.oldPath(),
                        change.contentChanged()))
                .toList();
        return new VerificationChangeSet(VerificationReport.Baseline.EXECUTION_DELTA,
                repository, changes, policyReport, architectureReport, defaultPolicyUsed,
                Optional.empty(), Optional.of(execution), limitations);
    }

    private static ChangeKind kind(ChangeStatus status) {
        return switch (status) {
            case ADDED -> ChangeKind.ADDED;
            case MODIFIED -> ChangeKind.MODIFIED;
            case DELETED -> ChangeKind.DELETED;
            case RENAMED -> ChangeKind.RENAMED;
            case UNTRACKED -> ChangeKind.UNTRACKED;
        };
    }

    private static ChangeKind kind(ExecutionChangeReport.ChangeType type) {
        return switch (type) {
            case ADDED -> ChangeKind.ADDED;
            case MODIFIED -> ChangeKind.MODIFIED;
            case DELETED -> ChangeKind.DELETED;
            case RENAMED -> ChangeKind.RENAMED;
            case GIT_STATE_CHANGED -> ChangeKind.GIT_STATE_CHANGED;
        };
    }

    public record VerificationChange(ChangeKind kind, String path, String oldPath, boolean contentChanged) {
        public VerificationChange {
            if (kind == null) throw new IllegalArgumentException("Verification change kind is required");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Verification change path is required");
            }
            if (kind == ChangeKind.RENAMED && (oldPath == null || oldPath.isBlank())) {
                throw new IllegalArgumentException("Verification rename old path is required");
            }
            if (kind != ChangeKind.RENAMED) oldPath = null;
        }
    }

    public enum ChangeKind {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        UNTRACKED,
        GIT_STATE_CHANGED
    }
}
