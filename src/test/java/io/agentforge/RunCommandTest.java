package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentforge.agent.CodingAgentType;
import io.agentforge.application.AgentExecutionService;
import io.agentforge.application.AgentRunException;
import io.agentforge.cli.RunCommand;
import io.agentforge.cli.RunRenderer;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

class RunCommandTest {
    @TempDir
    Path repository;

    @Test
    void constructsOneExactRequestAndCallsOrchestratorAndRendererExactlyOnce() {
        Fixture fixture = fixture(AgentRunStatus.PASS);

        Result result = fixture.execute(repository.toString(), "修复登录异常", "--agent", "CoDeX",
                "--timeout-seconds", "37", "--output", repository.getParent().resolve("report.md").toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("rendered run\n");
        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(fixture.service()).execute(request.capture());
        verify(fixture.renderer()).render(fixture.result());
        verifyNoMoreInteractions(fixture.service(), fixture.renderer());
        assertThat(request.getValue().repositoryRoot()).isEqualTo(repository.toAbsolutePath());
        assertThat(request.getValue().task()).isEqualTo("修复登录异常");
        assertThat(request.getValue().agentType()).isEqualTo(CodingAgentType.CODEX);
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(37));
        assertThat(request.getValue().environmentOverrides()).isEmpty();
        assertThat(request.getValue().outputFile()).isEqualTo(repository.getParent().resolve("report.md").toAbsolutePath());
    }

    @Test
    void optionalPathUsesCurrentDirectoryAndDocumentedDefaultTimeout() {
        Fixture fixture = fixture(AgentRunStatus.PASS);

        Result result = fixture.execute("分析登录流程", "--agent", "codex");

        assertThat(result.exitCode()).isZero();
        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(fixture.service()).execute(request.capture());
        assertThat(request.getValue().repositoryRoot()).isEqualTo(Path.of(".").toAbsolutePath().normalize());
        assertThat(request.getValue().timeout()).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void mapsOnlyAggregatePassAndWarningToZero() {
        assertThat(fixture(AgentRunStatus.PASS).execute("task", "--agent", "codex").exitCode()).isZero();
        assertThat(fixture(AgentRunStatus.WARNING).execute("task", "--agent", "codex").exitCode()).isZero();
        assertThat(fixture(AgentRunStatus.VERIFICATION_FAILED)
                .execute("task", "--agent", "codex").exitCode()).isOne();
        assertThat(fixture(AgentRunStatus.AGENT_FAILED)
                .execute("task", "--agent", "codex").exitCode()).isOne();
        assertThat(fixture(AgentRunStatus.AGENT_TIMED_OUT)
                .execute("task", "--agent", "codex").exitCode()).isOne();
        assertThat(fixture(AgentRunStatus.AGENT_INTERRUPTED)
                .execute("task", "--agent", "codex").exitCode()).isOne();
        assertThat(fixture(AgentRunStatus.AGENT_START_FAILED)
                .execute("task", "--agent", "codex").exitCode()).isOne();
    }

    @Test
    void rejectsUnknownAgentMissingTaskAndInvalidTimeoutWithoutCallingOrchestrator() {
        Fixture unknown = fixture(AgentRunStatus.PASS);
        Fixture missingTask = fixture(AgentRunStatus.PASS);
        Fixture invalidTimeout = fixture(AgentRunStatus.PASS);
        Fixture nonNumericTimeout = fixture(AgentRunStatus.PASS);

        Result unknownResult = unknown.execute("task", "--agent", "unknown");
        Result missingResult = missingTask.execute("--agent", "codex");
        Result invalidResult = invalidTimeout.execute("task", "--agent", "codex", "--timeout-seconds", "0");
        Result nonNumeric = nonNumericTimeout.execute(
                "task", "--agent", "codex", "--timeout-seconds", "later");

        assertThat(unknownResult.exitCode()).isEqualTo(2);
        assertThat(unknownResult.output()).contains("ERROR Unknown coding agent type: unknown");
        assertThat(missingResult.exitCode()).isEqualTo(2);
        assertThat(missingResult.output()).contains("Missing required parameter");
        assertThat(invalidResult.exitCode()).isEqualTo(2);
        assertThat(invalidResult.output()).contains("ERROR --timeout-seconds must be at least 1");
        assertThat(nonNumeric.exitCode()).isEqualTo(2);
        assertThat(nonNumeric.output()).contains("Invalid value for option '--timeout-seconds'");
        verify(unknown.service(), never()).execute(any());
        verify(missingTask.service(), never()).execute(any());
        verify(invalidTimeout.service(), never()).execute(any());
        verify(nonNumericTimeout.service(), never()).execute(any());
    }

    @Test
    void mapsOrchestratorInfrastructureFailureToInvocationErrorTwo() {
        AgentExecutionService service = mock(AgentExecutionService.class);
        RunRenderer renderer = mock(RunRenderer.class);
        AgentRunException failure = new AgentRunException(AgentRunException.Phase.REPOSITORY_VALIDATION,
                "Agent run failed during REPOSITORY_VALIDATION: missing repository",
                new IllegalArgumentException("missing repository"), null, null);
        when(service.execute(any())).thenThrow(failure);
        Fixture fixture = new Fixture(service, renderer, null);

        Result result = fixture.execute("missing", "task", "--agent", "codex");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Agent run failed during REPOSITORY_VALIDATION");
        verify(renderer, never()).render(any());
    }

    private Fixture fixture(AgentRunStatus status) {
        AgentExecutionService service = mock(AgentExecutionService.class);
        RunRenderer renderer = mock(RunRenderer.class);
        AgentRunResult result = mock(AgentRunResult.class);
        when(result.status()).thenReturn(status);
        when(service.execute(any())).thenReturn(result);
        when(renderer.render(result)).thenReturn("rendered run\n");
        return new Fixture(service, renderer, result);
    }

    private record Fixture(AgentExecutionService service, RunRenderer renderer, AgentRunResult result) {
        private Result execute(String... arguments) {
            StringWriter output = new StringWriter();
            CommandLine commandLine = new CommandLine(new RunCommand(service, renderer));
            commandLine.setOut(new PrintWriter(output));
            commandLine.setErr(new PrintWriter(output));
            return new Result(commandLine.execute(arguments), output.toString());
        }
    }

    private record Result(int exitCode, String output) {}
}
