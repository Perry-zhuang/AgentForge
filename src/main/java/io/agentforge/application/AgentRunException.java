package io.agentforge.application;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.model.ExecutionChangeReport;
import java.util.Optional;
import io.agentforge.runtrace.RunTraceReference;

/** Infrastructure/orchestration failure, distinct from agent process and boundary-verification results. */
public final class AgentRunException extends RuntimeException {
    private final Phase phase;
    private final AgentExecutionResult agentResult;
    private final ExecutionChangeReport executionDelta;
    private final RunTraceReference runTrace;

    public AgentRunException(Phase phase, String message, Throwable cause,
                             AgentExecutionResult agentResult, ExecutionChangeReport executionDelta) {
        super(message, cause);
        if (phase == null) throw new IllegalArgumentException("Agent run failure phase is required");
        this.phase = phase;
        this.agentResult = agentResult;
        this.executionDelta = executionDelta;
        this.runTrace = null;
    }

    public AgentRunException(Phase phase, String message, Throwable cause,
                             AgentExecutionResult agentResult, ExecutionChangeReport executionDelta,
                             RunTraceReference runTrace) {
        super(message, cause);
        if (phase == null) throw new IllegalArgumentException("Agent run failure phase is required");
        this.phase = phase;
        this.agentResult = agentResult;
        this.executionDelta = executionDelta;
        this.runTrace = runTrace;
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

    public Optional<RunTraceReference> runTrace() {
        return Optional.ofNullable(runTrace);
    }

    public enum Phase {
        REPOSITORY_VALIDATION,
        PLAN,
        PROMPT,
        BASELINE_CAPTURE,
        ADAPTER_RESOLUTION,
        AGENT_INVOCATION,
        TRACE_PREPARE,
        TRACE_PERSISTENCE,
        EXECUTION_DELTA,
        VERIFICATION
    }
}
