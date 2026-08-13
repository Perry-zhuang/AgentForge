package io.agentforge.planning;

import io.agentforge.model.ClassificationSource;
import io.agentforge.model.MutationMode;
import io.agentforge.model.RunPreflight;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.TaskClassification;
import io.agentforge.model.TaskType;
import org.springframework.stereotype.Component;

/** Pure preflight policy over already-computed classification facts. */
@Component
public class RunPreflightService {
    public RunPreflight evaluate(TaskClassification classification) {
        if (classification == null) throw new IllegalArgumentException("Task classification is required");
        if (classification.source() == ClassificationSource.USER_OVERRIDE
                && classification.explicitReadOnly()
                && classification.effectiveType() != TaskType.ANALYSIS) {
            return blocked(RunPreflight.Reason.TASK_TYPE_OVERRIDE_CONFLICTS_WITH_EXPLICIT_READ_ONLY_INTENT);
        }
        if (classification.effectiveType() == TaskType.UNKNOWN) {
            if (classification.mutationMode() == MutationMode.READ_ONLY) {
                return new RunPreflight(RunPreflight.Status.ALLOWED_WITH_WARNING,
                        RunPreflight.Reason.UNKNOWN_READ_ONLY,
                        "Task type could not be fully classified. Execution will continue in READ_ONLY mode.");
            }
            if (classification.securityImpact() == SecurityImpact.HIGH) {
                return blocked(RunPreflight.Reason.UNKNOWN_HIGH_SECURITY);
            }
            return blocked(RunPreflight.Reason.TASK_TYPE_UNRESOLVED);
        }
        boolean compatible = classification.effectiveType() == TaskType.ANALYSIS
                ? classification.mutationMode() == MutationMode.READ_ONLY
                : classification.mutationMode() == MutationMode.SOURCE_WRITE;
        return compatible
                ? new RunPreflight(RunPreflight.Status.ALLOWED,
                        RunPreflight.Reason.CLASSIFICATION_RESOLVED, "")
                : blocked(RunPreflight.Reason.TASK_TYPE_UNRESOLVED);
    }

    private RunPreflight blocked(RunPreflight.Reason reason) {
        return new RunPreflight(RunPreflight.Status.BLOCKED, reason, "");
    }
}
