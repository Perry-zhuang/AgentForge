package io.agentforge.model;

public record DependencyEdge(String from, String to, JavaRole fromRole, JavaRole toRole) {
    public String relation() {
        return fromRole.layer() + " -> " + toRole.layer();
    }
}
