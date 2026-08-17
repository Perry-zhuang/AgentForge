package io.agentforge.discovery;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.model.ExecutionChangeReport;

public record DiscoveryEvidence(DiscoveryDecision decision, String prompt, AgentExecutionResult agentResult,
                                ExecutionChangeReport delta, String rawResult, DiscoveryResult result,
                                DiscoveryValidation validation) {
    public DiscoveryEvidence {
        if (decision == null) throw new IllegalArgumentException("Discovery decision is required");
    }
    public static DiscoveryEvidence skipped(DiscoveryDecision decision) {
        return new DiscoveryEvidence(decision, null, null, null, null, null, null);
    }
}
