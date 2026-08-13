package io.agentforge.agent;

/** External process facts only; SUCCESS is not an AgentForge verification decision. */
public record AgentExecutionResult(
        CodingAgentIdentity agent,
        Integer processExitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean interrupted,
        AgentExecutionStatus status,
        String failureMessage
) {
    public AgentExecutionResult {
        if (agent == null || status == null) {
            throw new IllegalArgumentException("Agent identity and execution status are required");
        }
        if (stdout == null || stderr == null || failureMessage == null) {
            throw new IllegalArgumentException("Agent process output facts are required");
        }
        if (timedOut != (status == AgentExecutionStatus.TIMED_OUT)) {
            throw new IllegalArgumentException("Timed-out fact must match execution status");
        }
        if (interrupted != (status == AgentExecutionStatus.INTERRUPTED)) {
            throw new IllegalArgumentException("Interrupted fact must match execution status");
        }
        if (status == AgentExecutionStatus.START_FAILED && processExitCode != null) {
            throw new IllegalArgumentException("A process that did not start cannot have an exit code");
        }
        if (status == AgentExecutionStatus.SUCCESS && (processExitCode == null || processExitCode != 0)) {
            throw new IllegalArgumentException("Successful process status requires exit code zero");
        }
    }
}
