package io.agentforge.agent;

public interface CodingAgentAdapter {
    CodingAgentIdentity identity();

    AgentExecutionResult execute(AgentExecutionRequest request);
}
