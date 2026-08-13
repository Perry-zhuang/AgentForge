package io.agentforge.verification;

import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.FileType;
import io.agentforge.model.PolicyViolation;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationChangeSet;
import io.agentforge.model.VerificationChangeSet.VerificationChange;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import io.agentforge.model.ViolationSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CheckEngine {
    private static final List<String> CURRENT_WORKING_TREE_LIMITATIONS = List.of(
            "Check evaluates the current working tree.",
            "Pre-existing dirty changes cannot yet be distinguished from changes introduced by a specific Coding Agent execution.");
    private static final List<String> EXECUTION_LIMITATIONS = List.of(
            "Execution Check evaluates only changes introduced after the supplied baseline.",
            "Branch and HEAD changes are reported as facts and are not verification violations in P4 P2.");

    public VerificationReport verify(EngineeringPlan plan, DiffAuditReport diffAudit) {
        return verify(plan, VerificationChangeSet.currentWorkingTree(diffAudit));
    }

    public VerificationReport verify(EngineeringPlan plan, VerificationChangeSet changeSet) {
        if (plan == null) throw new IllegalArgumentException("Current EngineeringPlan is required");
        if (changeSet.baseline() == VerificationReport.Baseline.EXECUTION_DELTA) {
            throw new IllegalArgumentException("Execution verification requires a baseline engineering boundary");
        }
        return verify(plan, null, BoundaryFacts.from(plan), changeSet);
    }

    public VerificationReport verify(EngineeringBoundarySnapshot boundary, VerificationChangeSet changeSet) {
        if (boundary == null) throw new IllegalArgumentException("Execution engineering boundary is required");
        if (changeSet.baseline() != VerificationReport.Baseline.EXECUTION_DELTA) {
            throw new IllegalArgumentException("Baseline engineering boundary requires execution-delta facts");
        }
        return verify(null, boundary, BoundaryFacts.from(boundary), changeSet);
    }

    private VerificationReport verify(EngineeringPlan plan, EngineeringBoundarySnapshot executionBoundary,
                                      BoundaryFacts boundary, VerificationChangeSet changeSet) {
        validateFacts(boundary, changeSet);
        List<VerificationFinding> findings = new ArrayList<>();
        addScopeFindings(boundary, changeSet, findings);
        addPolicyFindings(changeSet, findings);
        addTaskTypeFindings(boundary, changeSet, findings);
        addArchitectureFindings(boundary, changeSet, findings);
        findings.sort(Comparator.comparing((VerificationFinding finding) -> finding.category().ordinal())
                .thenComparing(VerificationFinding::path)
                .thenComparing(VerificationFinding::code)
                .thenComparing(VerificationFinding::message));

        VerificationStatus scope = status(findings, VerificationFinding.Category.SCOPE);
        VerificationStatus policy = status(findings, VerificationFinding.Category.POLICY);
        VerificationStatus taskType = status(findings, VerificationFinding.Category.TASK_TYPE);
        VerificationStatus architecture = status(findings, VerificationFinding.Category.ARCHITECTURE);
        VerificationStatus overall = strictest(List.of(scope, policy, taskType, architecture));
        List<String> limitations = new ArrayList<>(changeSet.baseline()
                == VerificationReport.Baseline.CURRENT_WORKING_TREE
                ? CURRENT_WORKING_TREE_LIMITATIONS : EXECUTION_LIMITATIONS);
        limitations.addAll(changeSet.limitations());
        return new VerificationReport(plan, executionBoundary, changeSet.currentWorkingTreeAudit().orElse(null),
                changeSet.baseline(), scope, policy, taskType, architecture, overall, findings, limitations, changeSet);
    }

    private void validateFacts(BoundaryFacts boundary, VerificationChangeSet changeSet) {
        if (boundary.repositoryPath() != null
                && !boundary.repositoryPath().equals(changeSet.repository().path())) {
            throw new IllegalArgumentException("Engineering boundary and Git changes must refer to the same repository");
        }
        if (boundary.architectureConfigured() != changeSet.architectureReport().contractConfigured()) {
            throw new IllegalStateException("Architecture contract state changed while check was running");
        }
    }

    private void addScopeFindings(BoundaryFacts boundary, VerificationChangeSet changeSet,
                                  List<VerificationFinding> findings) {
        for (VerificationChange file : changeSet.changes()) {
            if (changeSet.baseline() == VerificationReport.Baseline.EXECUTION_DELTA
                    && file.kind() == VerificationChangeSet.ChangeKind.RENAMED) {
                ScopeLevel oldScope = boundary.classify(file.oldPath());
                ScopeLevel newScope = renameDestinationScope(boundary, file, oldScope);
                addScopeFinding(oldScope, file.oldPath(), findings);
                addScopeFinding(newScope, file.path(), findings);
            } else {
                for (String path : logicalPaths(file)) {
                    addScopeFinding(boundary.classify(path), path, findings);
                }
            }
        }
    }

    private ScopeLevel renameDestinationScope(BoundaryFacts boundary, VerificationChange file,
                                              ScopeLevel oldScope) {
        ScopeLevel explicit = boundary.classify(file.path());
        if (explicit != ScopeLevel.OUTSIDE) return explicit;
        if (oldScope != ScopeLevel.OUTSIDE && sameParent(file.oldPath(), file.path())) return oldScope;
        return ScopeLevel.OUTSIDE;
    }

    private void addScopeFinding(ScopeLevel scope, String path, List<VerificationFinding> findings) {
        if (scope == ScopeLevel.PRIMARY) return;
        if (scope == ScopeLevel.SECONDARY) {
            findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                    ViolationSeverity.WARN, "SECONDARY_SCOPE_MODIFIED", path,
                    "SECONDARY scope was modified and requires review."));
        } else if (scope == ScopeLevel.REFERENCE) {
            findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                    ViolationSeverity.ERROR, "REFERENCE_SCOPE_MODIFIED", path,
                    "REFERENCE scope is read-only by default."));
        } else {
            findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                    ViolationSeverity.ERROR, "OUT_OF_SCOPE_MODIFICATION", path,
                    "Changed path is outside PRIMARY, SECONDARY, and REFERENCE scope."));
        }
    }

    private boolean sameParent(String left, String right) {
        return parent(left).equals(parent(right));
    }

    private String parent(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    private void addPolicyFindings(VerificationChangeSet changeSet, List<VerificationFinding> findings) {
        for (PolicyViolation violation : changeSet.policyReport().violations()) {
            findings.add(new VerificationFinding(VerificationFinding.Category.POLICY,
                    violation.severity(), "POLICY_VIOLATION", violation.path(),
                    violation.rule() + " — " + violation.message()));
        }
    }

    private void addTaskTypeFindings(BoundaryFacts boundary, VerificationChangeSet changeSet,
                                     List<VerificationFinding> findings) {
        if (boundary.mutationMode() != io.agentforge.model.MutationMode.READ_ONLY) return;
        for (VerificationChange file : changeSet.changes()) {
            if (!file.contentChanged()) continue;
            for (String path : logicalPaths(file)) {
                if (!fileType(path).source()) continue;
                findings.add(new VerificationFinding(VerificationFinding.Category.TASK_TYPE,
                        ViolationSeverity.ERROR, "ANALYSIS_SOURCE_MODIFICATION", path,
                        "READ_ONLY tasks must not modify source code."));
            }
        }
    }

    private void addArchitectureFindings(BoundaryFacts boundary, VerificationChangeSet changeSet,
                                         List<VerificationFinding> findings) {
        if (!boundary.architectureConfigured()) return;
        for (DependencyEdge edge : changeSet.architectureReport().contractViolations()) {
            String path = changeSet.architectureReport().graph().nodes().containsKey(edge.from())
                    ? changeSet.architectureReport().graph().nodes().get(edge.from()).path() : "-";
            findings.add(new VerificationFinding(VerificationFinding.Category.ARCHITECTURE,
                    ViolationSeverity.ERROR, "ARCHITECTURE_CONSTRAINT_VIOLATION", path,
                    edge.fromRole().layer() + " must not depend on " + edge.toRole().layer()
                            + ": " + edge.from() + " -> " + edge.to()));
        }
    }

    private List<String> logicalPaths(VerificationChange file) {
        return file.oldPath() == null ? List.of(file.path()) : List.of(file.oldPath(), file.path());
    }

    private FileType fileType(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return FileType.classify(slash >= 0 ? path.substring(slash + 1) : path);
    }

    private VerificationStatus status(List<VerificationFinding> findings, VerificationFinding.Category category) {
        return findings.stream().filter(finding -> finding.category() == category)
                .map(finding -> finding.severity() == ViolationSeverity.ERROR
                        ? VerificationStatus.FAIL : VerificationStatus.WARNING)
                .reduce(VerificationStatus.PASS, this::strictest);
    }

    private VerificationStatus strictest(List<VerificationStatus> statuses) {
        return statuses.stream().reduce(VerificationStatus.PASS, this::strictest);
    }

    private VerificationStatus strictest(VerificationStatus left, VerificationStatus right) {
        if (left == VerificationStatus.FAIL || right == VerificationStatus.FAIL) return VerificationStatus.FAIL;
        if (left == VerificationStatus.WARNING || right == VerificationStatus.WARNING) {
            return VerificationStatus.WARNING;
        }
        return VerificationStatus.PASS;
    }

    private enum ScopeLevel {
        PRIMARY,
        SECONDARY,
        REFERENCE,
        OUTSIDE
    }

    private record BoundaryFacts(
            java.nio.file.Path repositoryPath,
            TaskType taskType,
            io.agentforge.model.MutationMode mutationMode,
            Set<String> primary,
            Set<String> secondary,
            Set<String> reference,
            boolean architectureConfigured
    ) {
        private static BoundaryFacts from(EngineeringPlan plan) {
            return new BoundaryFacts(plan.repository().repository().path(), plan.taskType(),
                    plan.taskClassification().mutationMode(),
                    pathsOf(plan.scope().primary()), pathsOf(plan.scope().secondary()),
                    pathsOf(plan.scope().reference()), plan.repository().architecture().contractConfigured());
        }

        private static BoundaryFacts from(EngineeringBoundarySnapshot boundary) {
            return new BoundaryFacts(null, boundary.taskType(), boundary.mutationMode(),
                    new LinkedHashSet<>(boundary.primaryPaths()),
                    new LinkedHashSet<>(boundary.secondaryPaths()), new LinkedHashSet<>(boundary.referencePaths()),
                    boundary.contractConfigured());
        }

        private ScopeLevel classify(String path) {
            String normalized = path.replace('\\', '/');
            if (primary.contains(normalized)) return ScopeLevel.PRIMARY;
            if (secondary.contains(normalized)) return ScopeLevel.SECONDARY;
            if (reference.contains(normalized)) return ScopeLevel.REFERENCE;
            return ScopeLevel.OUTSIDE;
        }

        private static Set<String> pathsOf(List<io.agentforge.model.RelevantFile> files) {
            Set<String> result = new LinkedHashSet<>();
            files.forEach(file -> result.add(file.path().replace('\\', '/')));
            return result;
        }
    }
}
