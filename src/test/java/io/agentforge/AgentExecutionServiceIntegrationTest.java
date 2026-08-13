package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.agent.AgentExecutionRequest;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentCapability;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentRegistry;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.application.AgentExecutionService;
import io.agentforge.application.CheckService;
import io.agentforge.application.DiffAuditService;
import io.agentforge.application.ExecutionBaselineService;
import io.agentforge.application.InspectService;
import io.agentforge.application.PlanService;
import io.agentforge.application.PromptService;
import io.agentforge.baseline.BaselineComparator;
import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.cli.PromptRenderer;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.git.GitClient;
import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.inspection.RelevantFileEngine;
import io.agentforge.inspection.TaskAnalyzer;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationStatus;
import io.agentforge.planning.PlanEngine;
import io.agentforge.planning.PlanRiskEngine;
import io.agentforge.planning.PlanTestAdvisor;
import io.agentforge.planning.TaskTypeClassifier;
import io.agentforge.policy.MinimalChangeEngine;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.prompting.PromptEngine;
import io.agentforge.risk.RiskEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.verification.CheckEngine;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import io.agentforge.runtrace.LocalRunTraceService;
import io.agentforge.runtrace.RunMarkdownRenderer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentExecutionServiceIntegrationTest {
    private static final CodingAgentIdentity CODEX = new CodingAgentIdentity(CodingAgentType.CODEX, "fake-codex",
            Set.of(CodingAgentCapability.NON_INTERACTIVE, CodingAgentCapability.REPOSITORY_WORKING_DIRECTORY));

    @TempDir
    Path temp;

    @Test
    void analysisNoChangePassesAndControlPlaneDoesNotMutateRepository() throws Exception {
        Path root = repository(temp.resolve("analysis-no-change"));
        RepositoryFacts before = facts(root);
        CapturingAdapter adapter = new CapturingAdapter(request -> {}, AgentExecutionStatus.SUCCESS);

        AgentRunResult result = service(adapter).execute(request(root, "分析登录流程"));

        assertThat(result.taskType()).isEqualTo(TaskType.ANALYSIS);
        assertThat(result.status()).isEqualTo(AgentRunStatus.PASS);
        assertThat(result.agentExecutionResult().status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.executionChangeReport().hasExecutionChanges()).isFalse();
        assertThat(result.verificationReport().overallStatus()).isEqualTo(VerificationStatus.PASS);
        assertThat(adapter.requests).hasSize(1);
        assertThat(Files.readString(result.runTrace().archiveDirectory().resolve("prompt.md")))
                .isEqualTo(adapter.requests.get(0).renderedPrompt());
        assertThat(sha(Files.readString(result.runTrace().archiveDirectory().resolve("prompt.md"))))
                .isEqualTo(sha(adapter.requests.get(0).renderedPrompt()));
        assertThat(facts(root)).isEqualTo(before);
    }

    @Test
    void analysisSourceModificationIsRejectedFromTheExecutionDelta() throws Exception {
        Path root = repository(temp.resolve("analysis-change"));
        CapturingAdapter adapter = new CapturingAdapter(request -> append(
                request.repositoryRoot().resolve("src/main/java/demo/LoginService.java"), "// agent change\n"),
                AgentExecutionStatus.SUCCESS);

        AgentRunResult result = service(adapter).execute(request(root, "分析登录流程"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.VERIFICATION_FAILED);
        assertThat(result.executionChangeReport().changes()).extracting(ExecutionChangeReport.ExecutionChange::path)
                .containsExactly("src/main/java/demo/LoginService.java");
        assertThat(result.verificationReport().findings()).extracting(finding -> finding.code())
                .contains("ANALYSIS_SOURCE_MODIFICATION");
    }

    @Test
    void bugfixPrimaryModificationPassesAgainstTheFrozenBoundary() throws Exception {
        Path root = repository(temp.resolve("bugfix-primary"));
        CapturingAdapter adapter = new CapturingAdapter(request -> append(
                request.repositoryRoot().resolve("src/main/java/demo/LoginService.java"), "// minimum fix\n"),
                AgentExecutionStatus.SUCCESS);

        AgentRunResult result = service(adapter).execute(request(root, "修复登录异常"));

        assertThat(result.taskType()).isEqualTo(TaskType.BUGFIX);
        assertThat(result.plan().scope().primary()).extracting(file -> file.path())
                .contains("src/main/java/demo/LoginService.java");
        assertThat(result.executionChangeReport().changes()).extracting(ExecutionChangeReport.ExecutionChange::path)
                .containsExactly("src/main/java/demo/LoginService.java");
        assertThat(result.verificationReport().overallStatus()).isEqualTo(VerificationStatus.PASS);
        assertThat(result.status()).isEqualTo(AgentRunStatus.PASS);
    }

    @Test
    void agentFailureAndBoundaryViolationAreBothRetained() throws Exception {
        Path root = repository(temp.resolve("failed-outside"));
        CapturingAdapter adapter = new CapturingAdapter(request -> write(request.repositoryRoot(),
                "src/main/java/demo/GeneratedOutsideService.java", "package demo; class GeneratedOutsideService {}\n"),
                AgentExecutionStatus.FAILED);

        AgentRunResult result = service(adapter).execute(request(root, "修复登录异常"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.AGENT_FAILED);
        assertThat(result.agentExecutionResult().processExitCode()).isEqualTo(9);
        assertThat(result.verificationReport().overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(result.verificationReport().findings()).extracting(finding -> finding.code())
                .contains("OUT_OF_SCOPE_MODIFICATION");
        assertThat(result.executionChangeReport().changes()).extracting(ExecutionChangeReport.ExecutionChange::path)
                .containsExactly("src/main/java/demo/GeneratedOutsideService.java");
        assertThat(Files.exists(root.resolve("src/main/java/demo/GeneratedOutsideService.java"))).isTrue();
        assertThat(Files.readString(result.runTrace().archiveDirectory().resolve("run.json")))
                .contains("\"status\" : \"FAILED\"", "\"status\" : \"COMPLETE\"");
    }

    @Test
    void timeoutAndBoundaryViolationAreBothRetained() throws Exception {
        Path root = repository(temp.resolve("timeout-outside"));
        CapturingAdapter adapter = new CapturingAdapter(request -> write(request.repositoryRoot(),
                "timeout-output.txt", "partial output\n"), AgentExecutionStatus.TIMED_OUT);

        AgentRunResult result = service(adapter).execute(request(root, "修复登录异常"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.AGENT_TIMED_OUT);
        assertThat(result.agentExecutionResult().timedOut()).isTrue();
        assertThat(result.verificationReport().overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(result.verificationReport().findings()).extracting(finding -> finding.code())
                .contains("OUT_OF_SCOPE_MODIFICATION");
        assertThat(Files.readString(result.runTrace().archiveDirectory().resolve("run.json")))
                .contains("\"status\" : \"TIMED_OUT\"", "\"status\" : \"COMPLETE\"");
    }

    @Test
    void historicalDirtyFilesAreExcludedAndOnlyAgentMutationIsAudited() throws Exception {
        Path root = repository(temp.resolve("historical-dirty"));
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// historical dirty work\n");
        CapturingAdapter adapter = new CapturingAdapter(request -> append(
                request.repositoryRoot().resolve("src/main/java/demo/LoginService.java"), "// execution-only fix\n"),
                AgentExecutionStatus.SUCCESS);

        AgentRunResult result = service(adapter).execute(request(root, "修复登录异常"));

        assertThat(result.executionChangeReport().changes()).extracting(ExecutionChangeReport.ExecutionChange::path)
                .containsExactly("src/main/java/demo/LoginService.java")
                .doesNotContain("src/main/java/demo/OutsideService.java");
        assertThat(result.verificationReport().findings()).extracting(finding -> finding.code())
                .doesNotContain("OUT_OF_SCOPE_MODIFICATION");
        assertThat(result.status()).isEqualTo(AgentRunStatus.PASS);
    }

    @Test
    void afterStateRelevantFileCannotRedefineThePreExecutionBoundary() throws Exception {
        Path root = repository(temp.resolve("boundary-drift"));
        String outside = "src/main/java/demo/OutsideEvilService.java";
        CapturingAdapter adapter = new CapturingAdapter(request -> write(request.repositoryRoot(), outside, """
                package demo;
                class OutsideEvilService {
                    // 登录 登录 登录 异常 异常 token auth login exception
                    void repairLoginAuthTokenException() { }
                }
                """), AgentExecutionStatus.SUCCESS);

        AgentRunResult result = service(adapter).execute(request(root, "修复登录异常"));

        assertThat(result.plan().scope().primary()).extracting(file -> file.path()).doesNotContain(outside);
        assertThat(result.plan().scope().secondary()).extracting(file -> file.path()).doesNotContain(outside);
        assertThat(result.plan().scope().reference()).extracting(file -> file.path()).doesNotContain(outside);
        assertThat(result.executionChangeReport().changes()).extracting(ExecutionChangeReport.ExecutionChange::path)
                .containsExactly(outside);
        assertThat(result.verificationReport().findings()).extracting(finding -> finding.code())
                .contains("OUT_OF_SCOPE_MODIFICATION");
        assertThat(result.status()).isEqualTo(AgentRunStatus.VERIFICATION_FAILED);
    }

    private AgentExecutionService service(CapturingAdapter adapter) {
        GitClient git = new GitClient();
        GitRepositoryInspector inspector = new GitRepositoryInspector(git);
        RepositoryScanner repositoryScanner = new RepositoryScanner(inspector);
        ContextScanner contextScanner = new ContextScanner();
        ArchitectureScanner architectureScanner = new ArchitectureScanner();
        ConfigLoader configLoader = new ConfigLoader();
        InspectService inspectService = new InspectService(repositoryScanner, contextScanner, architectureScanner,
                configLoader, new TaskAnalyzer(), new RelevantFileEngine());
        GitChangeScanner changeScanner = new GitChangeScanner(git, inspector);
        PlanService planService = new PlanService(inspectService, configLoader, changeScanner,
                new PlanEngine(new TaskTypeClassifier(), new PlanRiskEngine(), new PlanTestAdvisor(),
                        new io.agentforge.planning.RunPreflightService()));
        PromptService promptService = new PromptService(planService, new PromptEngine());
        RepositoryStateCapture stateCapture = new RepositoryStateCapture(git, inspector);
        RepositorySnapshotStore snapshotStore = new RepositorySnapshotStore();
        BaselineComparator comparator = new BaselineComparator();
        ExecutionBaselineService baselineService = new ExecutionBaselineService(planService, configLoader,
                stateCapture, snapshotStore, comparator);
        PolicyEngine policyEngine = new PolicyEngine();
        DiffAuditService diffService = new DiffAuditService(changeScanner, repositoryScanner, architectureScanner,
                configLoader, policyEngine, new MinimalChangeEngine(), new RiskEngine());
        CheckService checkService = new CheckService(planService, diffService, baselineService,
                repositoryScanner, architectureScanner, policyEngine,
                new ExecutionArchitectureAttributor(), new CheckEngine());
        return new AgentExecutionService(planService, promptService, new PromptRenderer(), baselineService,
                new CodingAgentRegistry(List.of(adapter)), checkService,
                new LocalRunTraceService(temp.resolve("traces"),
                        Clock.fixed(Instant.parse("2026-08-13T08:00:00Z"), ZoneOffset.UTC),
                        instant -> "integration-run-" + System.nanoTime(), new RunMarkdownRenderer()));
    }

    private AgentRunRequest request(Path root, String task) {
        return new AgentRunRequest(root, task, CodingAgentType.CODEX, Duration.ofSeconds(5), Map.of());
    }

    private Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "README.md", "# Demo\n");
        write(root, "src/main/java/demo/LoginService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class LoginService { // 登录 异常 流程
                }
                """);
        write(root, "src/main/java/demo/OutsideService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class OutsideService {
                }
                """);
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        return root.toRealPath();
    }

    private RepositoryFacts facts(Path root) throws Exception {
        return new RepositoryFacts(gitOutput(root, "branch", "--show-current"),
                gitOutput(root, "rev-parse", "HEAD"),
                gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all"), fileHashes(root));
    }

    private Map<String, String> fileHashes(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git"))).sorted().toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void append(Path path, String content) {
        try {
            Files.writeString(path, Files.readString(path) + content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void write(Path root, String relative, String content) {
        try {
            Path path = root.resolve(relative);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String gitOutput(Path root, String... arguments) throws Exception {
        Process process = new ProcessBuilder(concat("git", arguments)).directory(root.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private void git(Path root, String... arguments) throws Exception {
        gitOutput(root, arguments);
    }

    private String[] concat(String first, String[] remaining) {
        String[] result = new String[remaining.length + 1];
        result[0] = first;
        System.arraycopy(remaining, 0, result, 1, remaining.length);
        return result;
    }

    private static final class CapturingAdapter implements CodingAgentAdapter {
        private final Consumer<AgentExecutionRequest> action;
        private final AgentExecutionStatus status;
        private final List<AgentExecutionRequest> requests = new java.util.ArrayList<>();

        private CapturingAdapter(Consumer<AgentExecutionRequest> action, AgentExecutionStatus status) {
            this.action = action;
            this.status = status;
        }

        @Override
        public CodingAgentIdentity identity() {
            return CODEX;
        }

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            requests.add(request);
            action.accept(request);
            Integer exit = switch (status) {
                case SUCCESS -> 0;
                case FAILED -> 9;
                case TIMED_OUT, INTERRUPTED -> 143;
                case START_FAILED -> null;
            };
            return new AgentExecutionResult(CODEX, exit, "fake stdout", "fake stderr",
                    status == AgentExecutionStatus.TIMED_OUT, status == AgentExecutionStatus.INTERRUPTED,
                    status, status == AgentExecutionStatus.SUCCESS ? "" : "fake " + status);
        }
    }

    private record RepositoryFacts(String branch, String head, String status, Map<String, String> hashes) {}
}
