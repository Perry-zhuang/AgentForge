package io.agentforge.planning;

import io.agentforge.git.ChangeSignalClassifier;
import io.agentforge.model.AcceptanceCriterion;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EstimatedChangeScope;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PlanConstraint;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.PlanStep;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.model.TaskType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class PlanEngine {
    private static final Set<String> STRUCTURAL_SIGNALS = Set.of(
            "CLASS_OR_FILE", "SOURCE_AREA", "IMPORT", "DIRECT_DEPENDENCY_TYPE");
    private final TaskTypeClassifier taskTypeClassifier;
    private final PlanRiskEngine riskEngine;
    private final PlanTestAdvisor testAdvisor;

    public PlanEngine(TaskTypeClassifier taskTypeClassifier, PlanRiskEngine riskEngine,
                      PlanTestAdvisor testAdvisor) {
        this.taskTypeClassifier = taskTypeClassifier;
        this.riskEngine = riskEngine;
        this.testAdvisor = testAdvisor;
    }

    public EngineeringPlan create(TaskInspectionReport inspection, PolicyConfig policy, boolean defaultPolicyUsed,
                                  Optional<ArchitectureContract> contract,
                                  Optional<ChangeReport> currentChanges) {
        TaskTypeClassifier.Result classification = taskTypeClassifier.classify(inspection.request().task());
        ScopeResult scopeResult = scope(inspection);
        PlanConfidence confidence = confidence(inspection.relevantFiles());
        boolean reliableScope = !scopeResult.scope().primary().isEmpty();
        List<PlanConstraint> constraints = constraints(inspection, policy, contract);
        GitBaseline baseline = baseline(inspection, currentChanges);
        PlanScopeOverlap overlap = overlap(scopeResult.scope(), currentChanges);
        EstimatedChangeScope estimate = estimate(scopeResult.scope(), reliableScope);
        PlanRisk risk = riskEngine.evaluate(inspection, policy, baseline, overlap,
                scopeResult.primaryCandidateCount(), contract.isPresent());
        List<String> warnings = warnings(inspection, policy, defaultPolicyUsed, baseline, overlap, reliableScope);
        return new EngineeringPlan(inspection.request().task(), classification.type(), classification.signal(),
                confidence, inspection.repositoryReport(), baseline, scopeResult.scope(), overlap,
                steps(classification.type(), baseline, contract.isPresent(), reliableScope), constraints, estimate,
                risk, testAdvisor.advise(inspection.repositoryReport(), scopeResult.scope(), inspection.relatedFlow(),
                        classification.type(), reliableScope,
                        policy.readiness().testability().minimumTestRatio()),
                acceptanceCriteria(reliableScope), defaultPolicyUsed, reliableScope, warnings);
    }

    private ScopeResult scope(TaskInspectionReport inspection) {
        List<RelevantFile> ranked = inspection.relevantFiles();
        Set<String> flowPaths = flowPaths(inspection);
        Set<String> primaryCandidates = new LinkedHashSet<>();
        ranked.stream().filter(file -> file.level() == RelevanceLevel.HIGH)
                .forEach(file -> primaryCandidates.add(file.path()));
        ranked.stream().filter(file -> file.level() == RelevanceLevel.MEDIUM && flowPaths.contains(file.path()))
                .forEach(file -> primaryCandidates.add(file.path()));
        for (RelevantFile file : ranked) {
            if (primaryCandidates.size() >= 3) break;
            if (file.level() == RelevanceLevel.MEDIUM) primaryCandidates.add(file.path());
        }

        List<RelevantFile> primary = ranked.stream()
                .filter(file -> primaryCandidates.contains(file.path())).limit(5).toList();
        if (primary.isEmpty()) {
            return new ScopeResult(new PlanScope(List.of(), List.of(), ranked.stream().limit(5).toList()),
                    primaryCandidates.size());
        }
        Set<String> assigned = new LinkedHashSet<>();
        primary.forEach(file -> assigned.add(file.path()));
        List<RelevantFile> secondaryCandidates = new ArrayList<>();
        append(secondaryCandidates, ranked.stream()
                .filter(file -> primaryCandidates.contains(file.path()) && !assigned.contains(file.path())).toList());
        append(secondaryCandidates, ranked.stream()
                .filter(file -> file.level() == RelevanceLevel.MEDIUM && !primaryCandidates.contains(file.path()))
                .toList());
        append(secondaryCandidates, ranked.stream()
                .filter(file -> file.level() == RelevanceLevel.LOW && flowPaths.contains(file.path())).toList());
        List<RelevantFile> secondary = secondaryCandidates.stream()
                .filter(file -> !assigned.contains(file.path())).limit(5).toList();
        secondary.forEach(file -> assigned.add(file.path()));
        List<RelevantFile> reference = ranked.stream()
                .filter(file -> !assigned.contains(file.path())).limit(5).toList();
        return new ScopeResult(new PlanScope(primary, secondary, reference), primaryCandidates.size());
    }

    private void append(List<RelevantFile> target, List<RelevantFile> additions) {
        Set<String> existing = new HashSet<>();
        target.forEach(file -> existing.add(file.path()));
        additions.forEach(file -> {
            if (existing.add(file.path())) target.add(file);
        });
    }

    private Set<String> flowPaths(TaskInspectionReport inspection) {
        Set<String> result = new LinkedHashSet<>();
        var nodes = inspection.repositoryReport().architecture().graph().nodes();
        inspection.relatedFlow().forEach(edge -> {
            if (nodes.containsKey(edge.from())) result.add(nodes.get(edge.from()).path());
            if (nodes.containsKey(edge.to())) result.add(nodes.get(edge.to()).path());
        });
        return result;
    }

    private PlanConfidence confidence(List<RelevantFile> files) {
        long high = files.stream().filter(file -> file.level() == RelevanceLevel.HIGH).count();
        long medium = files.stream().filter(file -> file.level() == RelevanceLevel.MEDIUM).count();
        long structuralMedium = files.stream()
                .filter(file -> file.level() == RelevanceLevel.MEDIUM)
                .filter(file -> file.evidence().stream().anyMatch(item -> STRUCTURAL_SIGNALS.contains(item.signal())))
                .count();
        if (high >= 2 || structuralMedium >= 3) return PlanConfidence.HIGH;
        if (medium >= 2) return PlanConfidence.MEDIUM;
        return PlanConfidence.LOW;
    }

    private List<PlanConstraint> constraints(TaskInspectionReport inspection, PolicyConfig policy,
                                             Optional<ArchitectureContract> contract) {
        List<PlanConstraint> result = new ArrayList<>();
        Set<String> policyPaths = new LinkedHashSet<>();
        policy.change().deny().forEach(path -> {
            if (policyPaths.add(path)) result.add(new PlanConstraint(PlanConstraint.Kind.POLICY_DENY, path));
        });
        policy.change().protectedPaths().forEach(path -> {
            if (policyPaths.add(path)) result.add(new PlanConstraint(PlanConstraint.Kind.POLICY_PROTECTED, path));
        });
        if (contract.isPresent()) {
            ArchitectureContract.Architecture architecture = contract.get().architecture();
            architecture.layers().forEach(layer -> result.add(
                    new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_LAYER, layer)));
            for (String layer : architecture.layers()) {
                ArchitectureContract.LayerRule rule = architecture.rules().get(layer);
                if (rule == null) {
                    result.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_UNDECLARED,
                            layer + " -> NOT DECLARED"));
                } else if (rule.allowDependencies().isEmpty()) {
                    result.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_ALLOWED, layer + " -> NONE"));
                } else {
                    rule.allowDependencies().forEach(dependency -> result.add(
                            new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_ALLOWED,
                                    layer + " -> " + dependency)));
                }
            }
        } else {
            inspection.relatedFlow().forEach(edge -> result.add(new PlanConstraint(
                    PlanConstraint.Kind.OBSERVED_FLOW, edge.from() + " -> " + edge.to())));
        }
        return result;
    }

    private GitBaseline baseline(TaskInspectionReport inspection, Optional<ChangeReport> currentChanges) {
        RepositoryInfo repository = inspection.repositoryReport().repository();
        if (!repository.gitRepository()) return GitBaseline.unavailable();
        int changedFiles = currentChanges.map(change -> change.files().size()).orElse(0);
        boolean dirty = repository.dirty() || changedFiles > 0;
        return new GitBaseline(true, repository.branch(), repository.head(),
                dirty ? GitBaseline.WorkingTreeState.DIRTY : GitBaseline.WorkingTreeState.CLEAN, changedFiles);
    }

    private PlanScopeOverlap overlap(PlanScope scope, Optional<ChangeReport> currentChanges) {
        if (currentChanges.isEmpty() || currentChanges.get().files().isEmpty()) return PlanScopeOverlap.none();
        Set<String> primaryPaths = new LinkedHashSet<>(scope.primary().stream().map(RelevantFile::path).toList());
        Set<String> secondaryPaths = new LinkedHashSet<>(scope.secondary().stream().map(RelevantFile::path).toList());
        Set<String> changedPaths = new LinkedHashSet<>();
        int changedRelevantFiles = 0;
        boolean primaryOverlap = false;
        boolean secondaryOverlap = false;
        for (ChangedFile changed : currentChanges.get().files()) {
            List<String> logicalPaths = changed.oldPath() == null
                    ? List.of(changed.path()) : List.of(changed.path(), changed.oldPath());
            boolean changedPrimary = logicalPaths.stream().anyMatch(primaryPaths::contains);
            boolean changedSecondary = logicalPaths.stream().anyMatch(secondaryPaths::contains);
            if (changedPrimary || changedSecondary) changedRelevantFiles++;
            primaryOverlap |= changedPrimary;
            secondaryOverlap |= changedSecondary;
            changedPaths.addAll(logicalPaths);
        }
        List<String> overlappingPaths = new ArrayList<>();
        primaryPaths.stream().filter(changedPaths::contains).forEach(overlappingPaths::add);
        secondaryPaths.stream().filter(changedPaths::contains).forEach(overlappingPaths::add);
        return new PlanScopeOverlap(changedRelevantFiles, overlappingPaths, primaryOverlap, secondaryOverlap);
    }

    private EstimatedChangeScope estimate(PlanScope scope, boolean reliableScope) {
        if (!reliableScope) return new EstimatedChangeScope(0, 0, 0, false, false, false, false);
        List<RelevantFile> candidates = new ArrayList<>(scope.primary());
        candidates.addAll(scope.secondary());
        int minimum = scope.primary().size();
        int maximum = minimum + Math.min(3, scope.secondary().size());
        int modules = new TreeSet<>(candidates.stream().map(RelevantFile::module).toList()).size();
        ChangeSignalClassifier.Signals signals = ChangeSignalClassifier.classify(
                candidates.stream().map(RelevantFile::path).toList());
        return new EstimatedChangeScope(minimum, maximum, modules, signals.database(), signals.dependency(),
                signals.security(), signals.config());
    }

    private List<PlanStep> steps(TaskType type, GitBaseline baseline, boolean contractConfigured,
                                 boolean reliableScope) {
        List<String> descriptions = new ArrayList<>();
        if (!reliableScope) {
            descriptions.add("Refine the task description with a concrete class, module, or behavior.");
            descriptions.add("Run agentforge inspect again and require stronger evidence before implementation.");
            descriptions.add("Do not use LOW or REFERENCE evidence as a modification list.");
            descriptions.add("Review policy and architecture constraints before further planning.");
            return numbered(descriptions);
        }
        if (baseline.workingTree() == GitBaseline.WorkingTreeState.DIRTY) {
            descriptions.add("Review existing working-tree changes for overlap with PRIMARY scope.");
        }
        descriptions.add("Start from PRIMARY files.");
        descriptions.add("Use SECONDARY files to confirm surrounding behavior before changing them.");
        descriptions.add("Do not modify SECONDARY files unless PRIMARY analysis demonstrates that the change is required.");
        descriptions.add("Treat REFERENCE files as read-only context unless direct evidence requires changing them.");
        descriptions.addAll(switch (type) {
            case BUGFIX -> List.of(
                    "Reproduce or trace the current failure.",
                    "Identify the root cause.",
                    "Modify the minimum necessary code.",
                    "Preserve existing external behavior.");
            case FEATURE -> List.of(
                    "Confirm the current extension point.",
                    "Identify affected components within the likely scope.",
                    "Implement the minimum feature path.",
                    "Preserve compatibility.",
                    "Add focused tests.");
            case INTEGRATION -> List.of(
                    "Inspect the existing integration path.",
                    "Confirm the adapter or service boundary.",
                    "Reuse the existing contract.",
                    "Avoid duplicate integration logic.",
                    "Verify upstream and downstream behavior.");
            case REFACTOR -> List.of(
                    "Confirm the behavior baseline.",
                    "Keep changes inside the allowed scope.",
                    "Preserve external behavior.",
                    "Refactor within the established boundary.",
                    "Run regression tests.");
            case ANALYSIS -> List.of(
                    "Inspect the relevant files.",
                    "Trace the known dependency flow.",
                    "Document the findings.",
                    "Do not modify code unless explicitly requested.");
            case UNKNOWN -> List.of(
                    "Confirm the current task flow.",
                    "Reuse existing abstractions.",
                    "Prefer minimal changes.",
                    "Preserve existing API behavior.");
        });
        descriptions.add(contractConfigured
                ? "Respect architecture and policy constraints."
                : "Respect policy constraints and treat lightweight architecture findings as guidance, not a contract.");
        descriptions.add("Execute the generated Test Plan.");
        descriptions.add("Run agentforge diff after implementation.");
        return numbered(descriptions);
    }

    private List<PlanStep> numbered(List<String> descriptions) {
        List<PlanStep> result = new ArrayList<>();
        for (int index = 0; index < descriptions.size(); index++) {
            result.add(new PlanStep(index + 1, descriptions.get(index)));
        }
        return result;
    }

    private List<AcceptanceCriterion> acceptanceCriteria(boolean reliableScope) {
        List<AcceptanceCriterion> result = new ArrayList<>();
        if (!reliableScope) {
            result.add(new AcceptanceCriterion("A reliable implementation scope is derived before code changes."));
            result.add(new AcceptanceCriterion("LOW or REFERENCE evidence is not treated as permission to modify code."));
        }
        result.add(new AcceptanceCriterion("Relevant task behavior is implemented or verified."));
        result.add(new AcceptanceCriterion("Existing API compatibility is preserved."));
        result.add(new AcceptanceCriterion("No protected files are changed unless explicitly required."));
        result.add(new AcceptanceCriterion("No unrelated modules are modified."));
        result.add(new AcceptanceCriterion("Tests pass."));
        return result;
    }

    private List<String> warnings(TaskInspectionReport inspection, PolicyConfig policy, boolean defaultPolicyUsed,
                                  GitBaseline baseline, PlanScopeOverlap overlap, boolean reliableScope) {
        List<String> result = new ArrayList<>(inspection.warnings());
        if (defaultPolicyUsed) result.add("Built-in policy defaults are being used.");
        if (baseline.workingTree() == GitBaseline.WorkingTreeState.DIRTY) {
            result.add("Existing working tree changes detected.");
        }
        if (overlap.changedRelevantFiles() > 0) {
            String scope = overlap.primaryOverlap() ? "PRIMARY" : "SECONDARY";
            result.add(overlap.changedRelevantFiles() + " existing changed "
                    + (overlap.changedRelevantFiles() == 1 ? "file overlaps " : "files overlap ")
                    + "plan scope; " + scope + " overlap detected.");
        }
        if (!baseline.available()) result.add("Git baseline is not available because the target is not a Git repository.");
        if (testAdvisor.limitedTests(inspection.repositoryReport(),
                policy.readiness().testability().minimumTestRatio())) {
            result.add("Repository test coverage appears limited. Manual regression verification is recommended.");
        }
        if (!reliableScope) result.add("No reliable implementation scope could be derived.");
        return result.stream().distinct().toList();
    }

    private record ScopeResult(PlanScope scope, int primaryCandidateCount) {}
}
