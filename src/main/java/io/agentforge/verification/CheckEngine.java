package io.agentforge.verification;

import io.agentforge.model.ChangedFile;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.FileType;
import io.agentforge.model.PolicyViolation;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationFinding;
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
    private static final List<String> LIMITATIONS = List.of(
            "Check evaluates the current working tree.",
            "Pre-existing dirty changes cannot yet be distinguished from changes introduced by a specific Coding Agent execution.");

    public VerificationReport verify(EngineeringPlan plan, DiffAuditReport diffAudit) {
        validateFacts(plan, diffAudit);
        List<VerificationFinding> findings = new ArrayList<>();
        addScopeFindings(plan, diffAudit, findings);
        addPolicyFindings(diffAudit, findings);
        addTaskTypeFindings(plan, diffAudit, findings);
        addArchitectureFindings(plan, diffAudit, findings);
        findings.sort(Comparator.comparing((VerificationFinding finding) -> finding.category().ordinal())
                .thenComparing(VerificationFinding::path)
                .thenComparing(VerificationFinding::code)
                .thenComparing(VerificationFinding::message));

        VerificationStatus scope = status(findings, VerificationFinding.Category.SCOPE);
        VerificationStatus policy = status(findings, VerificationFinding.Category.POLICY);
        VerificationStatus taskType = status(findings, VerificationFinding.Category.TASK_TYPE);
        VerificationStatus architecture = status(findings, VerificationFinding.Category.ARCHITECTURE);
        VerificationStatus overall = strictest(List.of(scope, policy, taskType, architecture));
        return new VerificationReport(plan, diffAudit, VerificationReport.Baseline.CURRENT_WORKING_TREE,
                scope, policy, taskType, architecture, overall, findings, LIMITATIONS);
    }

    private void validateFacts(EngineeringPlan plan, DiffAuditReport diffAudit) {
        if (!plan.repository().repository().path().equals(diffAudit.changeReport().repository().path())) {
            throw new IllegalArgumentException("Plan and Git changes must refer to the same repository");
        }
        if (plan.repository().architecture().contractConfigured()
                != diffAudit.architectureReport().contractConfigured()) {
            throw new IllegalStateException("Architecture contract state changed while check was running");
        }
    }

    private void addScopeFindings(EngineeringPlan plan, DiffAuditReport diffAudit,
                                  List<VerificationFinding> findings) {
        Set<String> primary = paths(plan.scope().primary());
        Set<String> secondary = paths(plan.scope().secondary());
        Set<String> reference = paths(plan.scope().reference());
        for (ChangedFile file : diffAudit.changeReport().files()) {
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

    private void addPolicyFindings(DiffAuditReport diffAudit, List<VerificationFinding> findings) {
        for (PolicyViolation violation : diffAudit.policyReport().violations()) {
            findings.add(new VerificationFinding(VerificationFinding.Category.POLICY,
                    violation.severity(), "POLICY_VIOLATION", violation.path(),
                    violation.rule() + " — " + violation.message()));
        }
    }

    private void addTaskTypeFindings(EngineeringPlan plan, DiffAuditReport diffAudit,
                                     List<VerificationFinding> findings) {
        if (plan.taskType() != TaskType.ANALYSIS) return;
        for (ChangedFile file : diffAudit.changeReport().files()) {
            for (String path : logicalPaths(file)) {
                if (!fileType(path).source()) continue;
                findings.add(new VerificationFinding(VerificationFinding.Category.TASK_TYPE,
                        ViolationSeverity.ERROR, "ANALYSIS_SOURCE_MODIFICATION", path,
                        "ANALYSIS tasks must not modify source code by default."));
            }
        }
    }

    private void addArchitectureFindings(EngineeringPlan plan, DiffAuditReport diffAudit,
                                         List<VerificationFinding> findings) {
        if (!plan.repository().architecture().contractConfigured()) return;
        for (DependencyEdge edge : diffAudit.architectureReport().contractViolations()) {
            String path = diffAudit.architectureReport().graph().nodes().containsKey(edge.from())
                    ? diffAudit.architectureReport().graph().nodes().get(edge.from()).path() : "-";
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

    private List<String> logicalPaths(ChangedFile file) {
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
