package io.agentforge.application;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.AuditReport;
import io.agentforge.model.ContextReport;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.policy.ReadinessEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.RepositoryScanner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final RepositoryScanner repositoryScanner;
    private final ContextScanner contextScanner;
    private final ArchitectureScanner architectureScanner;
    private final ConfigLoader configLoader;
    private final ReadinessEngine readinessEngine;

    public AuditService(RepositoryScanner repositoryScanner, ContextScanner contextScanner,
                        ArchitectureScanner architectureScanner, ConfigLoader configLoader,
                        ReadinessEngine readinessEngine) {
        this.repositoryScanner = repositoryScanner;
        this.contextScanner = contextScanner;
        this.architectureScanner = architectureScanner;
        this.configLoader = configLoader;
        this.readinessEngine = readinessEngine;
    }

    public AuditReport audit(Path requestedPath) {
        RepositoryScanner.Result scan = repositoryScanner.scan(requestedPath);
        Path root = scan.repository().path();
        ConfigLoader.LoadedPolicy policy = configLoader.loadPolicy(root);
        Optional<ArchitectureContract> contract = configLoader.loadArchitecture(root);
        ContextReport context = contextScanner.scan(root, scan.files());
        ArchitectureReport architecture = architectureScanner.scan(root, scan.files(), contract);
        List<ScoreBreakdown> scores = readinessEngine.evaluate(root, scan.repository(), scan.structure(), context,
                architecture, policy.config(), !policy.defaultUsed());
        int overall = (int) Math.round(scores.stream().mapToInt(ScoreBreakdown::score).average().orElse(0));
        List<String> warnings = new ArrayList<>();
        warnings.addAll(scan.repository().warnings());
        warnings.addAll(scan.structure().warnings());
        warnings.addAll(context.warnings());
        warnings.addAll(architecture.warnings());
        if (policy.defaultUsed()) warnings.add("No .agentforge/policies.yaml; built-in defaults used");
        if (!contract.isPresent()) warnings.add("Architecture contract NOT CONFIGURED");
        if (context.agentsPresent() && context.agentsLines() > policy.config().readiness().context().agentsMaxLines()) {
            warnings.add("AGENTS.md is larger than " + policy.config().readiness().context().agentsMaxLines() + " lines");
        }
        if (!context.databaseRulesPresent()) warnings.add("No database change policy detected");
        if (!context.architectureDocumentPresent()) warnings.add("No architecture document detected");
        if (scan.structure().testFiles() == 0) warnings.add("Low test presence: no source tests detected");
        if (!architecture.possibleLayerBypasses().isEmpty()) warnings.add("Possible layer bypass detected");
        RepositoryReport repositoryReport = new RepositoryReport(scan.repository(), scan.technology(), scan.structure(), context, architecture);
        return new AuditReport(repositoryReport, scores, overall, warnings.stream().distinct().toList());
    }
}
