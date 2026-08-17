package io.agentforge.discovery;

import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.EngineeringPlan;

public interface DiscoveryCoordinator {
    DiscoveryOutcome discover(EngineeringPlan plan, AgentRunRequest request,
                              CodingAgentAdapter adapter, CodingAgentIdentity agent);

    static DiscoveryCoordinator disabled() {
        return (plan, request, adapter, agent) -> new DiscoveryOutcome(plan,
                DiscoveryEvidence.skipped(DiscoveryDecision.skipped()), null);
    }
}
