package io.agentforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.agent.process.DeterministicProcessExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdinProcessCodingAgentAdapterTest {
    private static final int LARGE_OUTPUT_SIZE = 128 * 1024;

    @Test
    void usesRepositoryAsWorkingDirectoryAndDeliversPromptUnchangedWhileCapturingBothStreams(
            @TempDir Path repository) throws Exception {
        String prompt = "# frozen\n保留 UTF-8 和尾随空格  \n";

        AgentExecutionResult result = execute(repository, prompt, "facts", Duration.ofSeconds(5), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.processExitCode()).isZero();
        assertThat(result.stdout()).contains("PWD=" + repository.toRealPath())
                .contains("PROMPT_SHA256=" + sha256(prompt))
                .contains("PROMPT_BYTES=" + prompt.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.stderr()).isEqualTo("stderr-captured");
        assertThat(result.failureMessage()).isEmpty();
    }

    @Test
    void capturesNonZeroProcessExitWithoutTreatingItAsVerification(@TempDir Path repository) {
        AgentExecutionResult result = execute(repository, "prompt", "non-zero", Duration.ofSeconds(5), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(result.processExitCode()).isEqualTo(23);
        assertThat(result.stdout()).isEqualTo("non-zero-stdout");
        assertThat(result.stderr()).isEqualTo("non-zero-stderr");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.interrupted()).isFalse();
    }

    @Test
    void drainsStdoutLargerThanPipeBufferWithoutDeadlock(@TempDir Path repository) {
        AgentExecutionResult result = execute(repository, "prompt", "large-stdout", Duration.ofSeconds(5), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.stdout()).hasSize(LARGE_OUTPUT_SIZE).containsOnlyOnce("O".repeat(LARGE_OUTPUT_SIZE));
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void drainsStderrLargerThanPipeBufferWithoutDeadlock(@TempDir Path repository) {
        AgentExecutionResult result = execute(repository, "prompt", "large-stderr", Duration.ofSeconds(5), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).hasSize(LARGE_OUTPUT_SIZE).containsOnlyOnce("E".repeat(LARGE_OUTPUT_SIZE));
    }

    @Test
    void drainsLargeStdoutAndStderrConcurrentlyWithoutDeadlock(@TempDir Path repository) {
        AgentExecutionResult result = execute(repository, "prompt", "large-both", Duration.ofSeconds(5), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.stdout()).hasSize(LARGE_OUTPUT_SIZE).containsOnlyOnce("O".repeat(LARGE_OUTPUT_SIZE));
        assertThat(result.stderr()).hasSize(LARGE_OUTPUT_SIZE).containsOnlyOnce("E".repeat(LARGE_OUTPUT_SIZE));
    }

    @Test
    void timeoutTerminatesAndReapsDirectChild(@TempDir Path repository) {
        AgentExecutionResult result = execute(repository, "prompt", "timeout", Duration.ofMillis(200), Map.of());

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.TIMED_OUT);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.interrupted()).isFalse();
        assertThat(result.processExitCode()).isNotNull();
        assertThat(result.stdout()).contains("started");
    }

    @Test
    void threadInterruptionTerminatesAndReturnsInterruptedFact(@TempDir Path repository) throws Exception {
        AtomicReference<AgentExecutionResult> captured = new AtomicReference<>();
        Thread worker = new Thread(() -> captured.set(execute(repository, "prompt", "timeout",
                Duration.ofSeconds(20), Map.of())));

        worker.start();
        Thread.sleep(200);
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().status()).isEqualTo(AgentExecutionStatus.INTERRUPTED);
        assertThat(captured.get().interrupted()).isTrue();
        assertThat(captured.get().timedOut()).isFalse();
        assertThat(captured.get().processExitCode()).isNotNull();
    }

    @Test
    void startFailureProducesDeterministicLifecycleResult(@TempDir Path repository) {
        CodingAgentIdentity identity = CodingAgentContractTest.identity();
        CodingAgentAdapter adapter = new StdinProcessCodingAgentAdapter(identity,
                List.of(repository.resolve("missing-agent-executable").toString()),
                new DeterministicProcessExecutor());

        AgentExecutionResult result = adapter.execute(request(identity, repository, "prompt",
                Duration.ofSeconds(1), Map.of()));

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.START_FAILED);
        assertThat(result.processExitCode()).isNull();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.failureMessage()).startsWith("Unable to start process:");
    }

    @Test
    void inheritsParentEnvironmentAndAppliesExplicitOverride(@TempDir Path repository) {
        AgentExecutionResult inherited = execute(repository, "prompt", "environment", Duration.ofSeconds(5),
                Map.of());
        AgentExecutionResult overridden = execute(repository, "prompt", "environment", Duration.ofSeconds(5),
                Map.of("AGENTFORGE_TEST_OVERRIDE", "child-value", "PATH", "agentforge-overridden-path"));

        assertThat(inherited.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(inherited.stdout()).contains("PATH_INHERITED=true");
        assertThat(overridden.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(overridden.stdout()).contains("PATH_VALUE=agentforge-overridden-path")
                .contains("OVERRIDE=child-value");
    }

    private AgentExecutionResult execute(Path repository, String prompt, String mode, Duration timeout,
                                         Map<String, String> environment) {
        CodingAgentIdentity identity = CodingAgentContractTest.identity();
        CodingAgentAdapter adapter = new StdinProcessCodingAgentAdapter(identity, childCommand(mode),
                new DeterministicProcessExecutor());
        return adapter.execute(request(identity, repository, prompt, timeout, environment));
    }

    private AgentExecutionRequest request(CodingAgentIdentity identity, Path repository, String prompt,
                                          Duration timeout, Map<String, String> environment) {
        return new AgentExecutionRequest(identity, repository, prompt, timeout, environment);
    }

    private List<String> childCommand(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return List.of(executable, "-cp", classpath, AgentProcessFixture.class.getName(), mode);
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
