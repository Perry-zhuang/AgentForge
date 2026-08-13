package io.agentforge.agent;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Explicit in-process type mapping; this is not runtime plugin discovery. */
public final class CodingAgentRegistry {
    private final Map<CodingAgentType, CodingAgentAdapter> adapters;

    public CodingAgentRegistry(Collection<? extends CodingAgentAdapter> adapters) {
        if (adapters == null) throw new IllegalArgumentException("Coding agent adapters are required");
        EnumMap<CodingAgentType, CodingAgentAdapter> registered = new EnumMap<>(CodingAgentType.class);
        for (CodingAgentAdapter adapter : adapters) {
            if (adapter == null) throw new IllegalArgumentException("Coding agent adapter is required");
            CodingAgentAdapter existing = registered.putIfAbsent(adapter.identity().type(), adapter);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate coding agent adapter for " + adapter.identity().type());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public CodingAgentAdapter resolve(CodingAgentType type) {
        if (type == null) throw new IllegalArgumentException("Coding agent type is required");
        CodingAgentAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("No coding agent adapter registered for " + type);
        }
        return adapter;
    }
}
