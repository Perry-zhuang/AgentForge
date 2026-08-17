package io.agentforge.model;

import java.util.List;
import java.util.Optional;

public record EngineeringPlan(
        String task,
        TaskType taskType,
        String taskTypeSignal,
        TaskClassification taskClassification,
        RunPreflight runPreflight,
        PlanConfidence confidence,
        RepositoryReport repository,
        GitBaseline gitBaseline,
        PlanScope scope,
        PlanScopeOverlap scopeOverlap,
        List<PlanStep> steps,
        List<PlanConstraint> constraints,
        EstimatedChangeScope estimatedChangeScope,
        PlanRisk preImplementationRisk,
        List<PlanTest> tests,
        List<AcceptanceCriterion> acceptanceCriteria,
        boolean defaultPolicyUsed,
        boolean reliableScope,
        List<String> warnings
) {
    public EngineeringPlan(String task, TaskType taskType, String taskTypeSignal, PlanConfidence confidence,
                           RepositoryReport repository, GitBaseline gitBaseline, PlanScope scope,
                           PlanScopeOverlap scopeOverlap, List<PlanStep> steps, List<PlanConstraint> constraints,
                           EstimatedChangeScope estimatedChangeScope, PlanRisk preImplementationRisk,
                           List<PlanTest> tests, List<AcceptanceCriterion> acceptanceCriteria,
                           boolean defaultPolicyUsed, boolean reliableScope, List<String> warnings) {
        this(task, taskType, taskTypeSignal, TaskClassification.legacy(taskType, taskTypeSignal),
                legacyPreflight(taskType), confidence,
                repository, gitBaseline, scope, scopeOverlap, steps, constraints, estimatedChangeScope,
                preImplementationRisk, tests, acceptanceCriteria, defaultPolicyUsed, reliableScope, warnings);
    }

    public EngineeringPlan {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Plan task is required");
        if (taskType == null || taskClassification == null || runPreflight == null || confidence == null || repository == null || gitBaseline == null
                || scope == null || scopeOverlap == null || estimatedChangeScope == null
                || preImplementationRisk == null) {
            throw new IllegalArgumentException("Engineering plan fields are required");
        }
        taskTypeSignal = taskTypeSignal == null || taskTypeSignal.isBlank() ? "None" : taskTypeSignal;
        if (taskType != taskClassification.effectiveType()) {
            throw new IllegalArgumentException("Plan task type must equal effective classification type");
        }
        steps = List.copyOf(steps);
        constraints = List.copyOf(constraints);
        tests = List.copyOf(tests);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        warnings = List.copyOf(warnings);
    }

    public EngineeringPlan withDiscoveryScope(List<String> primaryPaths, List<String> readOnlyPaths) {
        List<RelevantFile> primary = primaryPaths.stream().map(path -> discoveryFile(path, "Approved discovery PRIMARY")).toList();
        List<RelevantFile> reference = readOnlyPaths.stream().filter(path -> !primaryPaths.contains(path))
                .distinct().map(path -> discoveryFile(path, "Discovery read-only evidence")).toList();
        return new EngineeringPlan(task, taskType, taskTypeSignal, taskClassification, runPreflight, confidence,
                repository, gitBaseline, new PlanScope(primary, List.of(), reference), PlanScopeOverlap.none(),
                steps, constraints, estimatedChangeScope, preImplementationRisk, tests, acceptanceCriteria,
                defaultPolicyUsed, !primary.isEmpty(), warnings);
    }

    private RelevantFile discoveryFile(String path, String reason) {
        String module = repository.structure().modules().stream()
                .filter(candidate -> !candidate.equals(".") && path.startsWith(candidate + "/"))
                .max(java.util.Comparator.comparingInt(String::length)).orElse(".");
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return new RelevantFile(path, Optional.of(dot > 0 ? name.substring(0, dot) : name), module,
                FileResponsibility.OTHER, 0, new ConfidenceProfile(RelevanceLevel.HIGH, reason),
                List.of(), List.of(), List.of(new MatchEvidence("DISCOVERY", 0, reason)));
    }

    private static RunPreflight legacyPreflight(TaskType type) {
        return type == TaskType.UNKNOWN
                ? new RunPreflight(RunPreflight.Status.BLOCKED, RunPreflight.Reason.TASK_TYPE_UNRESOLVED, "")
                : new RunPreflight(RunPreflight.Status.ALLOWED, RunPreflight.Reason.CLASSIFICATION_RESOLVED, "");
    }
}
