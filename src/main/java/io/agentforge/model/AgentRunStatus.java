package io.agentforge.model;

import io.agentforge.agent.AgentExecutionStatus;

public enum AgentRunStatus {
    PASS,
    WARNING,
    AGENT_FAILED,
    AGENT_TIMED_OUT,
    AGENT_INTERRUPTED,
    AGENT_START_FAILED,
    VERIFICATION_FAILED;

    public static AgentRunStatus decide(AgentExecutionStatus agent, VerificationStatus verification) {
        if (agent == null || verification == null) {
            throw new IllegalArgumentException("Agent and verification statuses are required");
        }
        return switch (agent) {
            case FAILED -> AGENT_FAILED;
            case TIMED_OUT -> AGENT_TIMED_OUT;
            case INTERRUPTED -> AGENT_INTERRUPTED;
            case START_FAILED -> AGENT_START_FAILED;
            case SUCCESS -> switch (verification) {
                case PASS -> PASS;
                case WARNING -> WARNING;
                case FAIL -> VERIFICATION_FAILED;
            };
        };
    }
}
