package io.agentforge.verification;

import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DiffAuditReport;
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
        validateFacts(plan, changeSet);
        List<VerificationFinding> findings = new ArrayList<>();
        addScopeFindings(plan, changeSet, findings);
        addPolicyFindings(changeSet, findings);
        addTaskTypeFindings(plan, changeSet, findings);
        addArchitectureFindings(plan, changeSet, findings);
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
        return new VerificationReport(plan, changeSet.currentWorkingTreeAudit().orElse(null), changeSet.baseline(),
                scope, policy, taskType, architecture, overall, findings, limitations, changeSet);
    }

    private void validateFacts(EngineeringPlan plan, VerificationChangeSet changeSet) {
        if (!plan.repository().repository().path().equals(changeSet.repository().path())) {
            throw new IllegalArgumentException("Plan and Git changes must refer to the same repository");
        }
        if (plan.repository().architecture().contractConfigured()
                != changeSet.architectureReport().contractConfigured()) {
            throw new IllegalStateException("Architecture contract state changed while check was running");
        }
    }

    private void addScopeFindings(EngineeringPlan plan, VerificationChangeSet changeSet,
                                  List<VerificationFinding> findings) {
        Set<String> primary = paths(plan.scope().primary());
        Set<String> secondary = paths(plan.scope().secondary());
        Set<String> reference = paths(plan.scope().reference());
        for (VerificationChange file : changeSet.changes()) {
            for (String path : logicalPaths(file)) {
                if (primary.contains(path)) continue;
                if (secondary.contains(path)) {
                    findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                            ViolationSeverity.WARN, "SECONDARY_SCOPE_MODIFIED", path,
                            "SECONDARY scope was modified and requires review."));
                } else if (reference.contains(path)) {
                    findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                            ViolationSeverity.ERROR, "REFERENCE_SCOPE_MODIFIED", path,
                            "REFERENCE scope is read-only by default."));
                } else {
                    findings.add(new VerificationFinding(VerificationFinding.Category.SCOPE,
                            ViolationSeverity.ERROR, "OUT_OF_SCOPE_MODIFICATION", path,
                            "Changed path is outside PRIMARY, SECONDARY, and REFERENCE scope."));
                }
            }
        }
    }

    private void addPolicyFindings(VerificationChangeSet changeSet, List<VerificationFinding> findings) {
        for (PolicyViolation violation : changeSet.policyReport().violations()) {
            findings.add(new VerificationFinding(VerificationFinding.Category.POLICY,
                    violation.severity(), "POLICY_VIOLATION", violation.path(),
                    violation.rule() + " — " + violation.message()));
        }
    }

    private void addTaskTypeFindings(EngineeringPlan plan, VerificationChangeSet changeSet,
                                     List<VerificationFinding> findings) {
        if (plan.taskType() != TaskType.ANALYSIS) return;
        for (VerificationChange file : changeSet.changes()) {
            if (!file.contentChanged()) continue;
            for (String path : logicalPaths(file)) {
                if (!fileType(path).source()) continue;
                findings.add(new VerificationFinding(VerificationFinding.Category.TASK_TYPE,
                        ViolationSeverity.ERROR, "ANALYSIS_SOURCE_MODIFICATION", path,
                        "ANALYSIS tasks must not modify source code by default."));
            }
        }
    }

    private void addArchitectureFindings(EngineeringPlan plan, VerificationChangeSet changeSet,
                                         List<VerificationFinding> findings) {
        if (!plan.repository().architecture().contractConfigured()) return;
        for (DependencyEdge edge : changeSet.architectureReport().contractViolations()) {
            String path = changeSet.architectureReport().graph().nodes().containsKey(edge.from())
                    ? changeSet.architectureReport().graph().nodes().get(edge.from()).path() : "-";
            findings.add(new VerificationFinding(VerificationFinding.Category.ARCHITECTURE,
                    ViolationSeverity.ERROR, "ARCHITECTURE_CONSTRAINT_VIOLATION", path,
                    edge.fromRole().layer() + " must not depend on " + edge.toRole().layer()
                            + ": " + edge.from() + " -> " + edge.to()));
        }
    }

    private Set<String> paths(List<io.agentforge.model.RelevantFile> files) {
        Set<String> result = new LinkedHashSet<>();
        files.forEach(file -> result.add(file.path()));
        return result;
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
}
