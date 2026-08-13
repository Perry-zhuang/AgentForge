package io.agentforge;

import io.agentforge.application.AuditService;
import io.agentforge.application.AgentExecutionService;
import io.agentforge.application.BaselineService;
import io.agentforge.application.CheckService;
import io.agentforge.application.DiffAuditService;
import io.agentforge.application.ExecutionBaselineService;
import io.agentforge.application.InitService;
import io.agentforge.application.InspectService;
import io.agentforge.application.PlanService;
import io.agentforge.application.PromptService;
import io.agentforge.cli.AgentForgeCommand;
import io.agentforge.cli.AuditCommand;
import io.agentforge.cli.BaselineCommand;
import io.agentforge.cli.BaselineRenderer;
import io.agentforge.cli.CheckCommand;
import io.agentforge.cli.CheckRenderer;
import io.agentforge.cli.DiffCommand;
import io.agentforge.cli.InitCommand;
import io.agentforge.cli.InspectCommand;
import io.agentforge.cli.PlanCommand;
import io.agentforge.cli.PromptCommand;
import io.agentforge.cli.PromptRenderer;
import io.agentforge.cli.RunCommand;
import io.agentforge.cli.RunRenderer;
import io.agentforge.cli.TextReportRenderer;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentCapability;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentRegistry;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.config.ConfigLoader;
import io.agentforge.baseline.BaselineComparator;
import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.git.GitClient;
import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.policy.MinimalChangeEngine;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.policy.ReadinessEngine;
import io.agentforge.inspection.RelevantFileEngine;
import io.agentforge.inspection.TaskAnalyzer;
import io.agentforge.risk.RiskEngine;
import io.agentforge.planning.PlanEngine;
import io.agentforge.planning.PlanRiskEngine;
import io.agentforge.planning.PlanTestAdvisor;
import io.agentforge.planning.TaskTypeClassifier;
import io.agentforge.prompting.PromptEngine;
import io.agentforge.recommendation.RecommendationEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.verification.CheckEngine;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;

final class CliTestHarness {
    private CliTestHarness() {}

    static Result execute(String... args) {
        return executeWithAdapter(new NoOpCodingAgentAdapter(), args);
    }

    static Result executeWithAdapter(CodingAgentAdapter adapter, String... args) {
        GitClient git = new GitClient();
        GitRepositoryInspector inspector = new GitRepositoryInspector(git);
        RepositoryScanner repositoryScanner = new RepositoryScanner(inspector);
        ContextScanner contextScanner = new ContextScanner();
        ArchitectureScanner architectureScanner = new ArchitectureScanner();
        ConfigLoader configLoader = new ConfigLoader();
        ReadinessEngine readinessEngine = new ReadinessEngine();
        AuditService auditService = new AuditService(repositoryScanner, contextScanner, architectureScanner,
                configLoader, readinessEngine, new RecommendationEngine(readinessEngine));
        InspectService inspectService = new InspectService(repositoryScanner, contextScanner, architectureScanner,
                configLoader, new TaskAnalyzer(), new RelevantFileEngine());
        InitService initService = new InitService();
        GitChangeScanner changeScanner = new GitChangeScanner(git, inspector);
        PolicyEngine policyEngine = new PolicyEngine();
        DiffAuditService diffService = new DiffAuditService(changeScanner, repositoryScanner, architectureScanner,
                configLoader, policyEngine, new MinimalChangeEngine(), new RiskEngine());
        PlanService planService = new PlanService(inspectService, configLoader, changeScanner,
                new PlanEngine(new TaskTypeClassifier(), new PlanRiskEngine(), new PlanTestAdvisor()));
        PromptService promptService = new PromptService(planService, new PromptEngine());
        RepositoryStateCapture stateCapture = new RepositoryStateCapture(git, inspector);
        RepositorySnapshotStore snapshotStore = new RepositorySnapshotStore();
        BaselineComparator baselineComparator = new BaselineComparator();
        BaselineService baselineService = new BaselineService(stateCapture, snapshotStore, baselineComparator);
        ExecutionBaselineService executionBaselineService = new ExecutionBaselineService(planService, configLoader,
                stateCapture, snapshotStore, baselineComparator);
        CheckService checkService = new CheckService(planService, diffService, executionBaselineService,
                repositoryScanner, architectureScanner, policyEngine,
                new ExecutionArchitectureAttributor(), new CheckEngine());
        AgentExecutionService agentExecutionService = new AgentExecutionService(planService, promptService,
                new PromptRenderer(), executionBaselineService, new CodingAgentRegistry(List.of(adapter)),
                checkService);
        TextReportRenderer renderer = new TextReportRenderer();
        PromptRenderer promptRenderer = new PromptRenderer();
        CheckRenderer checkRenderer = new CheckRenderer();
        BaselineRenderer baselineRenderer = new BaselineRenderer();
        RunRenderer runRenderer = new RunRenderer();

        AuditCommand audit = new AuditCommand(auditService, renderer);
        InitCommand init = new InitCommand(initService, renderer);
        DiffCommand diff = new DiffCommand(diffService, renderer);
        InspectCommand inspect = new InspectCommand(inspectService, renderer);
        PlanCommand plan = new PlanCommand(planService, renderer);
        PromptCommand prompt = new PromptCommand(promptService, promptRenderer);
        CheckCommand check = new CheckCommand(checkService, checkRenderer);
        BaselineCommand baseline = new BaselineCommand(baselineService, executionBaselineService, baselineRenderer);
        RunCommand run = new RunCommand(agentExecutionService, runRenderer);
        Map<Class<?>, Object> components = Map.of(
                AuditCommand.class, audit,
                InitCommand.class, init,
                DiffCommand.class, diff,
                InspectCommand.class, inspect,
                PlanCommand.class, plan,
                PromptCommand.class, prompt,
                RunCommand.class, run,
                CheckCommand.class, check,
                BaselineCommand.class, baseline);
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

    private static final class NoOpCodingAgentAdapter implements CodingAgentAdapter {
        private static final CodingAgentIdentity IDENTITY = new CodingAgentIdentity(CodingAgentType.CODEX,
                "fake-codex", Set.of(CodingAgentCapability.NON_INTERACTIVE));

        @Override
        public CodingAgentIdentity identity() {
            return IDENTITY;
        }

        @Override
        public AgentExecutionResult execute(io.agentforge.agent.AgentExecutionRequest request) {
            return new AgentExecutionResult(IDENTITY, 0, "", "", false, false,
                    AgentExecutionStatus.SUCCESS, "");
        }
    }

    record Result(int exitCode, String output) {}
}
