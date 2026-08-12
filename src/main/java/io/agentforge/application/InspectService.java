package io.agentforge.application;

import io.agentforge.config.ConfigLoader;
import io.agentforge.inspection.RelevantFileEngine;
import io.agentforge.inspection.TaskAnalyzer;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ContextReport;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.model.TaskInspectionRequest;
import io.agentforge.model.TaskAnalysis;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.RepositoryScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class InspectService {
    private final RepositoryScanner repositoryScanner;
    private final ContextScanner contextScanner;
    private final ArchitectureScanner architectureScanner;
    private final ConfigLoader configLoader;
    private final TaskAnalyzer taskAnalyzer;
    private final RelevantFileEngine relevantFileEngine;

    public InspectService(RepositoryScanner repositoryScanner, ContextScanner contextScanner,
                          ArchitectureScanner architectureScanner, ConfigLoader configLoader,
                          TaskAnalyzer taskAnalyzer, RelevantFileEngine relevantFileEngine) {
        this.repositoryScanner = repositoryScanner;
        this.contextScanner = contextScanner;
        this.architectureScanner = architectureScanner;
        this.configLoader = configLoader;
        this.taskAnalyzer = taskAnalyzer;
        this.relevantFileEngine = relevantFileEngine;
    }

    public TaskInspectionReport inspect(TaskInspectionRequest request) {
        TaskAnalysis analysis = taskAnalyzer.analyze(request.task());
        if (analysis.empty()) throw new IllegalArgumentException("Task contains no meaningful keywords");
        RepositoryScanner.Result scan = repositoryScanner.scan(request.path());
        Path root = scan.repository().path();
        Optional<ArchitectureContract> contract = configLoader.loadArchitecture(root);
        ContextReport context = contextScanner.scan(root, scan.files());
        ArchitectureScanner.DetailedResult architecture = architectureScanner.scanDetailed(root, scan.files(), contract);
        RelevantFileEngine.Result relevance = relevantFileEngine.evaluate(root, scan.files(), scan.structure(), context,
                analysis, request.limit(), architecture.sourceIndex(), architecture.report().graph());
        RepositoryReport repository = new RepositoryReport(scan.repository(), scan.technology(), scan.structure(),
                context, architecture.report());
        List<String> warnings = new ArrayList<>();
        warnings.addAll(scan.repository().warnings());
        warnings.addAll(scan.structure().warnings());
        warnings.addAll(context.warnings());
        warnings.addAll(architecture.report().warnings());
        warnings.addAll(relevance.warnings());
        TaskInspectionRequest normalizedRequest = new TaskInspectionRequest(root, request.task(), request.limit());
        return new TaskInspectionReport(normalizedRequest, repository, analysis, relevance.relevantFiles(),
                relevance.relevantModules(), relevance.relevantSourceAreas(), relevance.relatedFlow(),
                warnings.stream().distinct().toList());
    }
}
