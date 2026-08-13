package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.agent.AgentExecutionRequest;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentCapability;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCliIntegrationTest {
    private static final CodingAgentIdentity CODEX = new CodingAgentIdentity(CodingAgentType.CODEX, "fake-codex",
            Set.of(CodingAgentCapability.NON_INTERACTIVE, CodingAgentCapability.REPOSITORY_WORKING_DIRECTORY));

    @TempDir
    Path temp;

    @Test
    void rootCliAnalysisNoChangePassesIsReadOnlyAndProducesByteIdenticalOutput() throws Exception {
        Path root = repository(temp.resolve("analysis-no-change"));
        RepositoryFacts before = facts(root);
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS, request -> {});

        CliTestHarness.Result first = run(adapter, root, "分析登录流程");
        CliTestHarness.Result second = run(adapter, root, "分析登录流程");
        CliTestHarness.Result third = run(adapter, root, "分析登录流程");

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).contains(
                "AgentForge Run",
                "Task Type                ANALYSIS",
                "Agent                    CODEX",
                "Status                   SUCCESS",
                "Files                    0",
                "Scope                    PASS",
                "Policy                   PASS",
                "Task-Type                PASS",
                "AGENT RUN PASS");
        assertThat(first.output()).isEqualTo(second.output()).isEqualTo(third.output());
        String firstSha = sha(first.output());
        String secondSha = sha(second.output());
        String thirdSha = sha(third.output());
        assertThat(firstSha).isEqualTo(secondSha).isEqualTo(thirdSha);
        System.out.println("RUN_CLI_DETERMINISM_SHA=" + firstSha + "," + secondSha + "," + thirdSha);
        assertThat(adapter.requests).hasSize(3);
        assertThat(facts(root)).isEqualTo(before);
    }

    @Test
    void analysisSourceModificationFailsEvenWhenAgentProcessSucceeds() throws Exception {
        Path root = repository(temp.resolve("analysis-source"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> append(request.repositoryRoot().resolve(firstScopePath(request, "PRIMARY")),
                        "// fake agent analysis mutation\n"));

        CliTestHarness.Result result = run(adapter, root, "分析登录流程");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "Status                   SUCCESS",
                "Files                    1",
                "Scope                    PASS",
                "Task-Type                FAIL",
                "ANALYSIS_SOURCE_MODIFICATION",
                "AGENT RUN VERIFICATION FAILED");
    }

    @Test
    void bugfixPrimaryPassesAndOnlyTheFakeAgentMutationIsAttributed() throws Exception {
        Path root = repository(temp.resolve("bugfix-primary"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> append(request.repositoryRoot().resolve(firstScopePath(request, "PRIMARY")),
                        "// fake agent minimum fix\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains(
                "Task Type                BUGFIX",
                "Status                   SUCCESS",
                "Files                    1",
                "Scope                    PASS",
                "Policy                   PASS",
                "Task-Type                PASS",
                "AGENT RUN PASS");
        assertThat(result.output()).doesNotContain("agentforge-before", "prompt.md", ".agentforge/run");
        assertThat(nonGitFiles(root)).containsExactlyInAnyOrder(
                "README.md", "pom.xml", "src/main/java/demo/LoginAuditService.java",
                "src/main/java/demo/LoginController.java", "src/main/java/demo/LoginCoordinator.java",
                "src/main/java/demo/LoginRepository.java", "src/main/java/demo/LoginService.java",
                "src/main/java/demo/LoginValidator.java", "src/main/java/demo/OutsideService.java");
    }

    @Test
    void secondaryModificationWarnsButReturnsZero() throws Exception {
        Path root = repository(temp.resolve("secondary-warning"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> append(request.repositoryRoot().resolve(firstScopePath(request, "SECONDARY")),
                        "// fake agent secondary mutation\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains(
                "Status                   SUCCESS",
                "Files                    1",
                "Scope                    WARNING",
                "SECONDARY_SCOPE_MODIFIED",
                "AGENT RUN WARNING");
    }

    @Test
    void successfulAgentOutsideScopeIsVerificationFailure() throws Exception {
        Path root = repository(temp.resolve("success-outside"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> append(request.repositoryRoot().resolve("src/main/java/demo/OutsideService.java"),
                        "// fake agent outside mutation\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "Status                   SUCCESS",
                "Files                    1",
                "Scope                    FAIL",
                "OUT_OF_SCOPE_MODIFICATION",
                "AGENT RUN VERIFICATION FAILED");
    }

    @Test
    void failedAgentStillShowsDeltaAndViolation() throws Exception {
        Path root = repository(temp.resolve("failed-outside"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.FAILED,
                request -> append(request.repositoryRoot().resolve("src/main/java/demo/OutsideService.java"),
                        "// fake agent failed after mutation\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "Status                   FAILED",
                "Exit Code                9",
                "Files                    1",
                "Overall                  FAIL",
                "OUT_OF_SCOPE_MODIFICATION",
                "AGENT RUN AGENT FAILED");
        assertThat(Files.readString(root.resolve("src/main/java/demo/OutsideService.java")))
                .contains("failed after mutation");
    }

    @Test
    void timedOutAgentStillShowsDeltaAndVerification() throws Exception {
        Path root = repository(temp.resolve("timeout-outside"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.TIMED_OUT,
                request -> append(request.repositoryRoot().resolve("src/main/java/demo/OutsideService.java"),
                        "// fake agent partial timeout mutation\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "Status                   TIMED_OUT",
                "Files                    1",
                "OUT_OF_SCOPE_MODIFICATION",
                "AGENT RUN TIMED OUT");
    }

    @Test
    void startFailureWithNoChangesRemainsRunFailureDespiteVerificationPass() throws Exception {
        Path root = repository(temp.resolve("start-failure"));
        RecordingAdapter adapter = adapter(AgentExecutionStatus.START_FAILED, request -> {});

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "Status                   START_FAILED",
                "Exit Code                N/A",
                "Files                    0",
                "Overall                  PASS",
                "AGENT RUN START FAILED");
    }

    @Test
    void historicalDirtyOutsideFileIsNotReportedWhenAgentChangesOnlyPrimary() throws Exception {
        Path root = repository(temp.resolve("historical-dirty"));
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// historical dirty work\n");
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> append(request.repositoryRoot().resolve(firstScopePath(request, "PRIMARY")),
                        "// fake agent execution-only fix\n"));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Files                    1", "AGENT RUN PASS")
                .doesNotContain("OutsideService.java", "OUT_OF_SCOPE_MODIFICATION");
    }

    @Test
    void afterStateHighRelevanceFileCannotExpandTheFrozenBoundary() throws Exception {
        Path root = repository(temp.resolve("boundary-drift"));
        String outside = "src/main/java/demo/OutsideEvilService.java";
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS,
                request -> write(request.repositoryRoot(), outside, """
                        package demo;
                        class OutsideEvilService {
                            // 登录 登录 登录 异常 异常 token auth login exception
                            void repairLoginAuthTokenException() { }
                        }
                        """));

        CliTestHarness.Result result = run(adapter, root, "修复登录异常");

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains(
                "ADDED " + outside,
                "OUT_OF_SCOPE_MODIFICATION " + outside,
                "AGENT RUN VERIFICATION FAILED");
        assertThat(adapter.requests).hasSize(1);
    }

    @Test
    void rootHelpIncludesRunAndEveryOldCommandWhileInvalidInvocationReturnsTwo() throws Exception {
        CliTestHarness.Result help = CliTestHarness.execute("--help");
        CliTestHarness.Result runHelp = CliTestHarness.execute("run", "--help");
        RecordingAdapter adapter = adapter(AgentExecutionStatus.SUCCESS, request -> {});
        Path root = repository(temp.resolve("invalid-cli"));

        CliTestHarness.Result unknown = CliTestHarness.executeWithAdapter(adapter,
                "run", root.toString(), "task", "--agent", "unknown");
        CliTestHarness.Result invalidTimeout = CliTestHarness.executeWithAdapter(adapter,
                "run", root.toString(), "task", "--agent", "codex", "--timeout-seconds", "0");
        CliTestHarness.Result missingTask = CliTestHarness.executeWithAdapter(adapter,
                "run", "--agent", "codex");
        CliTestHarness.Result invalidRepository = CliTestHarness.executeWithAdapter(adapter,
                "run", root.resolve("missing").toString(), "task", "--agent", "codex");

        assertThat(help.exitCode()).isZero();
        assertThat(help.output()).contains("audit", "init", "diff", "inspect", "plan", "prompt",
                "run", "check", "baseline");
        assertThat(runHelp.exitCode()).isZero();
        assertThat(runHelp.output()).contains("[PATH] TASK", "--agent", "--timeout-seconds")
                .doesNotContain("--model", "--sandbox", "--approval", "--api-key", "--auto-commit",
                        "--rollback", "--resume", "--parallel");
        assertThat(unknown.exitCode()).isEqualTo(2);
        assertThat(invalidTimeout.exitCode()).isEqualTo(2);
        assertThat(missingTask.exitCode()).isEqualTo(2);
        assertThat(invalidRepository.exitCode()).isEqualTo(2);
        assertThat(adapter.requests).isEmpty();
    }

    private CliTestHarness.Result run(RecordingAdapter adapter, Path root, String task) {
        return CliTestHarness.executeWithAdapter(adapter,
                "run", root.toString(), task, "--agent", "codex", "--timeout-seconds", "5");
    }

    private RecordingAdapter adapter(AgentExecutionStatus status, Consumer<AgentExecutionRequest> action) {
        return new RecordingAdapter(status, action);
    }

    private Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "README.md", "# Demo\n");
        for (String name : List.of("LoginService", "LoginController", "LoginRepository",
                "LoginCoordinator", "LoginValidator", "LoginAuditService")) {
            write(root, "src/main/java/demo/" + name + ".java", """
                    package demo;
                    import org.springframework.stereotype.Service;
                    @Service class %s { // 登录 异常 流程
                    }
                    """.formatted(name));
        }
        write(root, "src/main/java/demo/OutsideService.java", """
                package demo;
                class OutsideService { }
                """);
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        return root.toRealPath();
    }

    private String firstScopePath(AgentExecutionRequest request, String scope) {
        String prompt = request.renderedPrompt();
        int scopeStart = prompt.indexOf("## " + scope + " —");
        if (scopeStart < 0) throw new IllegalStateException("Missing " + scope + " prompt scope");
        int filesStart = prompt.indexOf("\nFiles:\n", scopeStart);
        int nextScope = prompt.indexOf("\n## ", filesStart + 1);
        String files = prompt.substring(filesStart, nextScope < 0 ? prompt.length() : nextScope);
        return files.lines().map(String::strip).filter(line -> line.startsWith("src/")).findFirst()
                .orElseThrow(() -> new IllegalStateException("No file in " + scope + " prompt scope"));
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

    private List<String> nonGitFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> !path.startsWith(root.resolve(".git")))
                    .map(root::relativize).map(Path::toString).map(path -> path.replace('\\', '/')).sorted().toList();
        }
    }

    private String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
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
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private void git(Path root, String... arguments) throws Exception {
        gitOutput(root, arguments);
    }

    private static final class RecordingAdapter implements CodingAgentAdapter {
        private final AgentExecutionStatus status;
        private final Consumer<AgentExecutionRequest> action;
        private final List<AgentExecutionRequest> requests = new ArrayList<>();

        private RecordingAdapter(AgentExecutionStatus status, Consumer<AgentExecutionRequest> action) {
            this.status = status;
            this.action = action;
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
