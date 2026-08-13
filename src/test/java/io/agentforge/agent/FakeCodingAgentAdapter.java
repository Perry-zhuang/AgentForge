package io.agentforge.agent;

final class FakeCodingAgentAdapter implements CodingAgentAdapter {
    private final CodingAgentIdentity identity;
    private AgentExecutionRequest received;

    FakeCodingAgentAdapter(CodingAgentIdentity identity) {
        this.identity = identity;
    }

    @Override
    public CodingAgentIdentity identity() {
        return identity;
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        received = request;
        return new AgentExecutionResult(identity, 0, "fake stdout", "fake stderr", false, false,
                AgentExecutionStatus.SUCCESS, "");
    }

    AgentExecutionRequest received() {
        return received;
    }
}
