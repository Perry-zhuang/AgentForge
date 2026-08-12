package io.agentforge.application;

import io.agentforge.config.ConfigLoader;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.policy.MinimalChangeEngine;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.risk.RiskEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.RepositoryScanner;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DiffAuditService {
    private final GitChangeScanner gitChangeScanner;
    private final RepositoryScanner repositoryScanner;
    private final ArchitectureScanner architectureScanner;
    private final ConfigLoader configLoader;
    private final PolicyEngine policyEngine;
    private final MinimalChangeEngine minimalChangeEngine;
    private final RiskEngine riskEngine;

    public DiffAuditService(GitChangeScanner gitChangeScanner, RepositoryScanner repositoryScanner,
                            ArchitectureScanner architectureScanner, ConfigLoader configLoader,
                            PolicyEngine policyEngine, MinimalChangeEngine minimalChangeEngine, RiskEngine riskEngine) {
        this.gitChangeScanner = gitChangeScanner;
        this.repositoryScanner = repositoryScanner;
        this.architectureScanner = architectureScanner;
        this.configLoader = configLoader;
        this.policyEngine = policyEngine;
        this.minimalChangeEngine = minimalChangeEngine;
        this.riskEngine = riskEngine;
    }

    public DiffAuditReport audit(Path requestedPath) {
        ChangeReport change = gitChangeScanner.scan(requestedPath);
        Path root = change.repository().path();
        ConfigLoader.LoadedPolicy policy = configLoader.loadPolicy(root);
        RepositoryScanner.Result repository = repositoryScanner.scan(root);
        ArchitectureReport fullArchitecture = architectureScanner.scan(root, repository.files(), configLoader.loadArchitecture(root));
        ArchitectureReport changedArchitecture = changedOnly(fullArchitecture, change);
        PolicyReport policyReport = policyEngine.evaluate(change, policy.config(), changedArchitecture);
        ScoreBreakdown minimal = minimalChangeEngine.evaluate(change, policy.config().minimalChange());
        return new DiffAuditReport(change, policyReport, minimal,
                riskEngine.evaluate(change, policyReport, policy.config()), changedArchitecture, policy.defaultUsed());
    }

    private ArchitectureReport changedOnly(ArchitectureReport report, ChangeReport change) {
        Set<String> changedPaths = new HashSet<>();
        change.files().forEach(file -> {
            changedPaths.add(file.path());
            if (file.oldPath() != null) changedPaths.add(file.oldPath());
        });
        return new ArchitectureReport(report.graph(), report.roleCounts(), report.relationCounts(),
                report.possibleLayerBypasses().stream()
                        .filter(edge -> changedPaths.contains(report.graph().nodes().get(edge.from()).path())).toList(),
                report.contractViolations().stream()
                        .filter(edge -> changedPaths.contains(report.graph().nodes().get(edge.from()).path())).toList(),
                report.contractConfigured(), report.analysisMode(), report.warnings());
    }
}
