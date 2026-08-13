package io.agentforge.agent;

import io.agentforge.agent.process.DeterministicProcessExecutor;
import io.agentforge.agent.process.DeterministicProcessExecutor.ProcessRequest;
import io.agentforge.agent.process.DeterministicProcessExecutor.ProcessResult;
import java.util.List;

/** Contract implementation for an explicitly configured command that accepts the rendered prompt on stdin. */
public final class StdinProcessCodingAgentAdapter implements CodingAgentAdapter {
    private final CodingAgentIdentity identity;
    private final List<String> command;
    private final DeterministicProcessExecutor processExecutor;

    public StdinProcessCodingAgentAdapter(CodingAgentIdentity identity, List<String> command,
                                          DeterministicProcessExecutor processExecutor) {
        if (identity == null || command == null || processExecutor == null) {
            throw new IllegalArgumentException("Agent identity, command, and process executor are required");
        }
        if (!identity.capabilities().contains(CodingAgentCapability.PROMPT_VIA_STDIN)) {
            throw new IllegalArgumentException("Stdin process adapter requires PROMPT_VIA_STDIN capability");
        }
        this.identity = identity;
        this.command = List.copyOf(command);
        this.processExecutor = processExecutor;
    }

    @Override
    public CodingAgentIdentity identity() {
        return identity;
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        if (request == null) throw new IllegalArgumentException("Agent execution request is required");
        if (!identity.equals(request.agent())) {
            throw new IllegalArgumentException("Execution request identity does not match adapter identity");
        }
        ProcessResult process = processExecutor.execute(new ProcessRequest(command, request.repositoryRoot(),
                request.renderedPrompt(), request.timeout(), request.environmentOverrides()));
        AgentExecutionStatus status = status(process);
        return new AgentExecutionResult(identity, process.exitCode(), process.stdout(), process.stderr(),
                process.timedOut(), process.interrupted(), status, process.failureMessage());
    }

    private AgentExecutionStatus status(ProcessResult result) {
        if (result.startFailed()) return AgentExecutionStatus.START_FAILED;
        if (result.interrupted()) return AgentExecutionStatus.INTERRUPTED;
        if (result.timedOut()) return AgentExecutionStatus.TIMED_OUT;
        if (result.exitCode() != null && result.exitCode() == 0 && result.failureMessage().isEmpty()) {
            return AgentExecutionStatus.SUCCESS;
        }
        return AgentExecutionStatus.FAILED;
    }
}
