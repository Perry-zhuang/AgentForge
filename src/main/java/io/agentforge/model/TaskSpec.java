package io.agentforge.model;

import java.util.Comparator;
import java.util.List;

/** Minimal immutable intent contract frozen before agent execution. */
public record TaskSpec(
        String taskId,
        String originalTask,
        TaskType taskType,
        String goal,
        List<ExpectedChange> expectedChanges,
        List<String> forbiddenChanges,
        List<String> acceptanceCriteria
) {
    public TaskSpec {
        if (taskId == null || taskId.isBlank() || originalTask == null || originalTask.isBlank()
                || taskType == null || goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("TaskSpec identity, task type, and goal are required");
        }
        goal = goal.strip();
        expectedChanges = expectedChanges.stream()
                .sorted(Comparator.comparing(ExpectedChange::path)
                        .thenComparing(change -> change.expectation().ordinal()))
                .toList();
        if (expectedChanges.stream().map(ExpectedChange::path).distinct().count() != expectedChanges.size()) {
            throw new IllegalArgumentException("TaskSpec expected paths must be unique");
        }
        forbiddenChanges = forbiddenChanges.stream().distinct().sorted().toList();
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }
}
