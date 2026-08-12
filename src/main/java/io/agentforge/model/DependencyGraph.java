package io.agentforge.model;

import java.util.List;
import java.util.Map;

public record DependencyGraph(Map<String, JavaTypeInfo> nodes, List<DependencyEdge> edges) {
    public DependencyGraph {
        nodes = Map.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
