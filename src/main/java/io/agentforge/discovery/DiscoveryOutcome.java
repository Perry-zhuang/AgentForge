package io.agentforge.discovery;

import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.RepositorySnapshot;

public record DiscoveryOutcome(EngineeringPlan plan, DiscoveryEvidence evidence,
                               RepositorySnapshot verifiedRepositoryState) {
    public DiscoveryOutcome {
        if (plan == null || evidence == null) throw new IllegalArgumentException("Discovery outcome is required");
    }
}
