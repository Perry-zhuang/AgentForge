package io.agentforge.model;

import java.util.List;

public record EngineeringPrompt(
        String task,
        TaskType taskType,
        TaskClassification taskClassification,
        PlanConfidence planConfidence,
        RepositoryReport repository,
        GitBaseline gitBaseline,
        String objective,
        PromptScopeSection primaryScope,
        PromptScopeSection secondaryScope,
        PromptScopeSection referenceScope,
        List<String> architectureInstructions,
        List<String> policyInstructions,
        List<String> doNotTouchPaths,
        List<String> workingTreeInstructions,
        List<String> changeInstructions,
        PlanRisk preImplementationRisk,
        List<String> riskInstructions,
        List<String> verificationInstructions,
        List<String> acceptanceCriteria,
        List<String> warnings,
        List<String> finalReportSections,
        List<String> finalReportInstructions
) {
    public EngineeringPrompt {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Engineering prompt task is required");
        if (taskType == null || taskClassification == null || planConfidence == null || repository == null || gitBaseline == null
                || objective == null || objective.isBlank() || primaryScope == null || secondaryScope == null
                || referenceScope == null || preImplementationRisk == null) {
            throw new IllegalArgumentException("Engineering prompt fields are required");
        }
        if (taskType != taskClassification.effectiveType()) {
            throw new IllegalArgumentException("Prompt task type must equal effective classification type");
        }
        architectureInstructions = List.copyOf(architectureInstructions);
        policyInstructions = List.copyOf(policyInstructions);
        doNotTouchPaths = List.copyOf(doNotTouchPaths);
        workingTreeInstructions = List.copyOf(workingTreeInstructions);
        changeInstructions = List.copyOf(changeInstructions);
        riskInstructions = List.copyOf(riskInstructions);
        verificationInstructions = List.copyOf(verificationInstructions);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        warnings = List.copyOf(warnings);
        finalReportSections = List.copyOf(finalReportSections);
        finalReportInstructions = List.copyOf(finalReportInstructions);
    }
}
