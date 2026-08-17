package io.agentforge.discovery;

import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.TaskClassification;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record DiscoveryRequest(
        String task, String taskHash, TaskClassification classification, Path repositoryRoot,
        String branch, String head, List<String> inspectEvidence, List<String> proposedPrimaryScope,
        List<String> proposedSecondaryScope, List<String> referenceFiles, List<String> existingDirtyPaths,
        PolicyConfig policy, ArchitectureAnalysisMode architectureAnalysisMode, Duration timeout
) {
    public DiscoveryRequest {
        if (task == null || task.isBlank() || taskHash == null || !taskHash.matches("[0-9a-f]{64}")
                || classification == null || repositoryRoot == null || branch == null || head == null
                || inspectEvidence == null || proposedPrimaryScope == null || proposedSecondaryScope == null
                || referenceFiles == null || existingDirtyPaths == null || policy == null
                || architectureAnalysisMode == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Discovery request facts are required");
        }
        repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        inspectEvidence = List.copyOf(inspectEvidence);
        proposedPrimaryScope = List.copyOf(proposedPrimaryScope);
        proposedSecondaryScope = List.copyOf(proposedSecondaryScope);
        referenceFiles = List.copyOf(referenceFiles);
        existingDirtyPaths = List.copyOf(existingDirtyPaths);
    }
}
