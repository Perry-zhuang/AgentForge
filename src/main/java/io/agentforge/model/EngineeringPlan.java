package io.agentforge.model;

import java.util.List;

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

    private static RunPreflight legacyPreflight(TaskType type) {
        return type == TaskType.UNKNOWN
                ? new RunPreflight(RunPreflight.Status.BLOCKED, RunPreflight.Reason.TASK_TYPE_UNRESOLVED, "")
                : new RunPreflight(RunPreflight.Status.ALLOWED, RunPreflight.Reason.CLASSIFICATION_RESOLVED, "");
    }
}
