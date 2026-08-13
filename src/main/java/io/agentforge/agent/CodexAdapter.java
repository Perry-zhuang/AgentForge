package io.agentforge.agent;

import io.agentforge.agent.process.DeterministicProcessExecutor;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

/** Drives the non-interactive Codex CLI using an already prepared execution request. */
public final class CodexAdapter implements CodingAgentAdapter {
    private static final String DEFAULT_EXECUTABLE = "codex";
    private static final CodingAgentIdentity IDENTITY = new CodingAgentIdentity(
            CodingAgentType.CODEX,
            "codex",
            EnumSet.of(
                    CodingAgentCapability.NON_INTERACTIVE,
                    CodingAgentCapability.PROMPT_VIA_STDIN,
                    CodingAgentCapability.REPOSITORY_WORKING_DIRECTORY,
                    CodingAgentCapability.ENVIRONMENT_OVERRIDES,
                    CodingAgentCapability.EXECUTION_TIMEOUT));

    private final String executable;
    private final DeterministicProcessExecutor processExecutor;

    /** Uses the executable name {@code codex} resolved by the child process environment. */
    public CodexAdapter(DeterministicProcessExecutor processExecutor) {
        this(DEFAULT_EXECUTABLE, processExecutor);
    }

    /** Uses an explicit executable path without probing, installing, upgrading, or authenticating Codex. */
    public CodexAdapter(Path executable, DeterministicProcessExecutor processExecutor) {
        this(executable == null ? null : executable.toString(), processExecutor);
    }

    private CodexAdapter(String executable, DeterministicProcessExecutor processExecutor) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("Codex executable is required");
        }
        if (processExecutor == null) throw new IllegalArgumentException("Process executor is required");
        this.executable = executable;
        this.processExecutor = processExecutor;
    }

    @Override
    public CodingAgentIdentity identity() {
        return IDENTITY;
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        return new StdinProcessCodingAgentAdapter(identity(), command(request), processExecutor).execute(request);
    }

    List<String> command(AgentExecutionRequest request) {
        if (request == null) throw new IllegalArgumentException("Agent execution request is required");
        if (!identity().equals(request.agent())) {
            throw new IllegalArgumentException("Execution request identity does not match Codex adapter identity");
        }
        return List.of(executable, "exec", "--sandbox", "workspace-write",
                "-C", request.repositoryRoot().toString(), "-");
    }
}
