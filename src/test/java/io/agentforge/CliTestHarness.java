package io.agentforge;

import io.agentforge.application.AuditService;
import io.agentforge.application.DiffAuditService;
import io.agentforge.application.InitService;
import io.agentforge.cli.AgentForgeCommand;
import io.agentforge.cli.AuditCommand;
import io.agentforge.cli.DiffCommand;
import io.agentforge.cli.InitCommand;
import io.agentforge.cli.TextReportRenderer;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.git.GitClient;
import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.policy.MinimalChangeEngine;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.policy.ReadinessEngine;
import io.agentforge.risk.RiskEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.RepositoryScanner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import picocli.CommandLine;

final class CliTestHarness {
    private CliTestHarness() {}

    static Result execute(String... args) {
        GitClient git = new GitClient();
        GitRepositoryInspector inspector = new GitRepositoryInspector(git);
        RepositoryScanner repositoryScanner = new RepositoryScanner(inspector);
        ContextScanner contextScanner = new ContextScanner();
        ArchitectureScanner architectureScanner = new ArchitectureScanner();
        ConfigLoader configLoader = new ConfigLoader();
        ReadinessEngine readinessEngine = new ReadinessEngine();
        AuditService auditService = new AuditService(repositoryScanner, contextScanner, architectureScanner,
                configLoader, readinessEngine);
        InitService initService = new InitService();
        GitChangeScanner changeScanner = new GitChangeScanner(git, inspector);
        DiffAuditService diffService = new DiffAuditService(changeScanner, repositoryScanner, architectureScanner,
                configLoader, new PolicyEngine(), new MinimalChangeEngine(), new RiskEngine());
        TextReportRenderer renderer = new TextReportRenderer();

        AuditCommand audit = new AuditCommand(auditService, renderer);
        InitCommand init = new InitCommand(initService, renderer);
        DiffCommand diff = new DiffCommand(diffService, renderer);
        Map<Class<?>, Object> components = Map.of(
                AuditCommand.class, audit,
                InitCommand.class, init,
                DiffCommand.class, diff);
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) {
                Object component = components.get(type);
                if (component == null) throw new IllegalArgumentException("No test component for " + type.getName());
                return type.cast(component);
            }
        };

        StringWriter output = new StringWriter();
        CommandLine commandLine = new CommandLine(new AgentForgeCommand(), factory);
        commandLine.setOut(new PrintWriter(output));
        commandLine.setErr(new PrintWriter(output));
        int exit = commandLine.execute(args);
        return new Result(exit, output.toString());
    }

    record Result(int exitCode, String output) {}
}
