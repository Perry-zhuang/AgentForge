package io.agentforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.agent.process.DeterministicProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAdapterTest {
    @Test
    void exposesOnlyImplementedCodexCapabilities() {
        CodexAdapter adapter = new CodexAdapter(new DeterministicProcessExecutor());

        assertThat(adapter.identity().type()).isEqualTo(CodingAgentType.CODEX);
        assertThat(adapter.identity().id()).isEqualTo("codex");
        assertThat(adapter.identity().capabilities()).containsExactlyInAnyOrder(
                CodingAgentCapability.NON_INTERACTIVE,
                CodingAgentCapability.PROMPT_VIA_STDIN,
                CodingAgentCapability.REPOSITORY_WORKING_DIRECTORY,
                CodingAgentCapability.ENVIRONMENT_OVERRIDES,
                CodingAgentCapability.EXECUTION_TIMEOUT);
    }

    @Test
    void constructsMinimalDeterministicCommandWithExplicitRepositoryAndStdin(@TempDir Path repository)
            throws Exception {
        Path executable = Path.of("/opt/agentforge/bin/codex-test");
        CodexAdapter adapter = new CodexAdapter(executable, new DeterministicProcessExecutor());
        AgentExecutionRequest request = request(adapter, repository, "prompt", Duration.ofSeconds(7), Map.of());

        List<String> first = adapter.command(request);
        List<String> second = adapter.command(request);

        assertThat(first).containsExactly(executable.toString(), "exec", "--sandbox", "workspace-write",
                "-C", repository.toRealPath().toString(), "-");
        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain("--dangerously-bypass-approvals-and-sandbox",
                "--dangerously-bypass-hook-trust", "danger-full-access", "--yolo", "--full-auto",
                "--ask-for-approval", "-a");
    }

    @Test
    void defaultConstructionFallsBackToCodexExecutableName(@TempDir Path repository) {
        CodexAdapter adapter = new CodexAdapter(new DeterministicProcessExecutor());

        assertThat(adapter.command(request(adapter, repository, "prompt", Duration.ofSeconds(1), Map.of())))
                .startsWith("codex", "exec");
    }

    @Test
    void registryMapsCodexTypeToRealAdapter() {
        CodexAdapter adapter = new CodexAdapter(new DeterministicProcessExecutor());
        CodingAgentRegistry registry = new CodingAgentRegistry(List.of(adapter));

        assertThat(registry.resolve(CodingAgentType.CODEX)).isSameAs(adapter);
    }

    @Test
    void fakeCodexOsProcessReceivesExactCommandCwdPromptTimeoutAndEnvironment(@TempDir Path root)
            throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path evidence = Files.createDirectory(root.resolve("evidence"));
        Files.writeString(repository.resolve("README.md"), "unchanged repository\n", StandardCharsets.UTF_8);
        String repositoryBefore = repositoryDigest(repository);
        Path executable = fakeCodex(root);
        CodexAdapter adapter = new CodexAdapter(executable, new DeterministicProcessExecutor());
        String prompt = "  # prepared prompt\n保留原文\r\ntrailing spaces  \n";
        Map<String, String> environment = Map.of(
                "AGENTFORGE_FAKE_CODEX_EVIDENCE", evidence.toString(),
                "AGENTFORGE_FAKE_CODEX_ENV", "explicit-value");

        AgentExecutionResult result = adapter.execute(
                request(adapter, repository, prompt, Duration.ofSeconds(5), environment));

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.processExitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("fake-codex-stdout");
        assertThat(result.stderr()).isEqualTo("fake-codex-stderr");
        assertThat(Files.readAllLines(evidence.resolve("argv.txt"))).containsExactly(
                "exec", "--sandbox", "workspace-write", "-C", repository.toRealPath().toString(), "-");
        assertThat(Files.readString(evidence.resolve("pwd.txt"))).isEqualTo(repository.toRealPath().toString());
        assertThat(Files.readString(evidence.resolve("environment.txt"))).isEqualTo("explicit-value");
        byte[] delivered = Files.readAllBytes(evidence.resolve("stdin.bin"));
        assertThat(delivered).isEqualTo(prompt.getBytes(StandardCharsets.UTF_8));
        assertThat(sha256(delivered)).isEqualTo(sha256(prompt.getBytes(StandardCharsets.UTF_8)));
        assertThat(repositoryDigest(repository)).isEqualTo(repositoryBefore);
        try (var files = Files.list(repository)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("README.md");
        }
    }

    @Test
    void mapsNonZeroExitAndPreservesBothStreams(@TempDir Path root) throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path evidence = Files.createDirectory(root.resolve("evidence"));
        CodexAdapter adapter = new CodexAdapter(fakeCodex(root), new DeterministicProcessExecutor());

        AgentExecutionResult result = adapter.execute(request(adapter, repository, "prompt", Duration.ofSeconds(5),
                Map.of("AGENTFORGE_FAKE_CODEX_EVIDENCE", evidence.toString(),
                        "AGENTFORGE_FAKE_CODEX_EXIT", "19")));

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.FAILED);
        assertThat(result.processExitCode()).isEqualTo(19);
        assertThat(result.stdout()).isEqualTo("fake-codex-stdout");
        assertThat(result.stderr()).isEqualTo("fake-codex-stderr");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void mapsTimeoutUsingRequestTimeout(@TempDir Path root) throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path evidence = Files.createDirectory(root.resolve("evidence"));
        CodexAdapter adapter = new CodexAdapter(fakeCodex(root), new DeterministicProcessExecutor());

        AgentExecutionResult result = adapter.execute(request(adapter, repository, "prompt", Duration.ofMillis(150),
                Map.of("AGENTFORGE_FAKE_CODEX_EVIDENCE", evidence.toString(),
                        "AGENTFORGE_FAKE_CODEX_MODE", "timeout")));

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.TIMED_OUT);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.interrupted()).isFalse();
        assertThat(result.processExitCode()).isNotNull();
    }

    @Test
    void mapsMissingExecutableToStartFailure(@TempDir Path repository) {
        CodexAdapter adapter = new CodexAdapter(repository.resolve("missing-codex"),
                new DeterministicProcessExecutor());

        AgentExecutionResult result = adapter.execute(
                request(adapter, repository, "prompt", Duration.ofSeconds(1), Map.of()));

        assertThat(result.status()).isEqualTo(AgentExecutionStatus.START_FAILED);
        assertThat(result.processExitCode()).isNull();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.failureMessage()).startsWith("Unable to start process:");
    }

    private AgentExecutionRequest request(CodexAdapter adapter, Path repository, String prompt, Duration timeout,
                                          Map<String, String> environment) {
        return new AgentExecutionRequest(adapter.identity(), repository, prompt, timeout, environment);
    }

    private Path fakeCodex(Path root) throws IOException {
        Path executable = root.resolve("fake-codex");
        Files.writeString(executable, """
                #!/bin/sh
                evidence="$AGENTFORGE_FAKE_CODEX_EVIDENCE"
                printf '%s\n' "$@" > "$evidence/argv.txt"
                printf '%s' "$PWD" > "$evidence/pwd.txt"
                printf '%s' "$AGENTFORGE_FAKE_CODEX_ENV" > "$evidence/environment.txt"
                cat > "$evidence/stdin.bin"
                if [ "$AGENTFORGE_FAKE_CODEX_MODE" = "timeout" ]; then
                  exec /bin/sleep 30
                fi
                printf 'fake-codex-stdout'
                printf 'fake-codex-stderr' >&2
                exit "${AGENTFORGE_FAKE_CODEX_EXIT:-0}"
                """, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return executable;
    }

    private String repositoryDigest(Path repository) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(repository)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(repository.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
