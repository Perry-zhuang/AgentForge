package io.agentforge.model;

import java.util.List;

public record TaskInspectionReport(
        TaskInspectionRequest request,
        RepositoryReport repositoryReport,
        TaskAnalysis taskAnalysis,
        List<RelevantFile> relevantFiles,
        List<RelevantModule> relevantModules,
        List<RelevantSourceArea> relevantSourceAreas,
        List<DependencyEdge> relatedFlow,
        List<String> warnings
) {
    public TaskInspectionReport {
        relevantFiles = List.copyOf(relevantFiles);
        relevantModules = List.copyOf(relevantModules);
        relevantSourceAreas = List.copyOf(relevantSourceAreas);
        relatedFlow = List.copyOf(relatedFlow);
        warnings = List.copyOf(warnings);
    }
}
