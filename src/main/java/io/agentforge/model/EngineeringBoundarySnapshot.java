package io.agentforge.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Immutable, verification-relevant engineering boundary captured before execution. */
@JsonPropertyOrder({"task", "taskType", "primaryPaths", "secondaryPaths", "referencePaths",
        "changePolicy", "defaultPolicyUsed", "architectureAnalysisMode", "architectureContract"})
public record EngineeringBoundarySnapshot(
        String task,
        TaskType taskType,
        List<String> primaryPaths,
        List<String> secondaryPaths,
        List<String> referencePaths,
        PolicyConfig.ChangePolicy changePolicy,
        boolean defaultPolicyUsed,
        ArchitectureAnalysisMode architectureAnalysisMode,
        ArchitectureContract architectureContract
) {
    public EngineeringBoundarySnapshot {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Boundary task is required");
        if (taskType == null || changePolicy == null || architectureAnalysisMode == null) {
            throw new IllegalArgumentException("Engineering boundary facts are required");
        }
        task = task.strip();
        primaryPaths = normalizedPaths(primaryPaths, "PRIMARY");
        secondaryPaths = normalizedPaths(secondaryPaths, "SECONDARY");
        referencePaths = normalizedPaths(referencePaths, "REFERENCE");
    }

    public static EngineeringBoundarySnapshot capture(
            EngineeringPlan plan,
            PolicyConfig.ChangePolicy changePolicy,
            boolean defaultPolicyUsed,
            Optional<ArchitectureContract> contract
    ) {
        if (plan == null || changePolicy == null || contract == null) {
            throw new IllegalArgumentException("Plan, policy, and architecture facts are required");
        }
        return new EngineeringBoundarySnapshot(plan.task(), plan.taskType(),
                paths(plan.scope().primary()), paths(plan.scope().secondary()), paths(plan.scope().reference()),
                changePolicy, defaultPolicyUsed, plan.repository().architecture().analysisMode(),
                contract.orElse(null));
    }

    public boolean contractConfigured() {
        return architectureContract != null;
    }

    public boolean matchesTask(String requestedTask) {
        return requestedTask != null && task.equals(requestedTask.strip());
    }

    private static List<String> paths(List<RelevantFile> files) {
        return files.stream().map(RelevantFile::path).toList();
    }

    private static List<String> normalizedPaths(List<String> paths, String scope) {
        if (paths == null) throw new IllegalArgumentException(scope + " boundary paths are required");
        List<String> normalized = paths.stream().map(path -> normalizePath(path, scope)).distinct()
                .sorted(Comparator.naturalOrder()).toList();
        if (normalized.size() != paths.size()) {
            throw new IllegalArgumentException("Duplicate " + scope + " boundary path");
        }
        return normalized;
    }

    private static String normalizePath(String path, String scope) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(scope + " boundary path is required");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../")) {
            throw new IllegalArgumentException(scope + " boundary path must be repository-relative: " + path);
        }
        return normalized;
    }
}
