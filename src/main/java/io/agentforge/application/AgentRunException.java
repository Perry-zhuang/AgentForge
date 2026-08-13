package io.agentforge.application;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.model.ExecutionChangeReport;
import java.util.Optional;

/** Infrastructure/orchestration failure, distinct from agent process and boundary-verification results. */
public final class AgentRunException extends RuntimeException {
    private final Phase phase;
    private final AgentExecutionResult agentResult;
    private final ExecutionChangeReport executionDelta;

    public AgentRunException(Phase phase, String message, Throwable cause,
                             AgentExecutionResult agentResult, ExecutionChangeReport executionDelta) {
        super(message, cause);
        if (phase == null) throw new IllegalArgumentException("Agent run failure phase is required");
        this.phase = phase;
        this.agentResult = agentResult;
        this.executionDelta = executionDelta;
    }

    public Phase phase() {
        return phase;
    }

    public Optional<AgentExecutionResult> agentResult() {
        return Optional.ofNullable(agentResult);
    }

    public Optional<ExecutionChangeReport> executionDelta() {
        return Optional.ofNullable(executionDelta);
    }

    public enum Phase {
        REPOSITORY_VALIDATION,
        PLAN,
        PROMPT,
        BASELINE_CAPTURE,
        ADAPTER_RESOLUTION,
        AGENT_INVOCATION,
        EXECUTION_DELTA,
        VERIFICATION
    }
}
