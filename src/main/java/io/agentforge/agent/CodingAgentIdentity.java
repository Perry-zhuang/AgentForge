package io.agentforge.agent;

import java.util.Set;

public record CodingAgentIdentity(
        CodingAgentType type,
        String id,
        Set<CodingAgentCapability> capabilities
) {
    public CodingAgentIdentity {
        if (type == null) throw new IllegalArgumentException("Coding agent type is required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Coding agent id is required");
        if (capabilities == null) throw new IllegalArgumentException("Coding agent capabilities are required");
        id = id.strip();
        capabilities = Set.copyOf(capabilities);
    }
}
