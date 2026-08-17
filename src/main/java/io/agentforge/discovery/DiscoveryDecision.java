package io.agentforge.discovery;

import java.util.List;

public record DiscoveryDecision(Status status, List<Reason> reasons) {
    public DiscoveryDecision {
        if (status == null || reasons == null) throw new IllegalArgumentException("Discovery decision facts are required");
        reasons = List.copyOf(reasons);
        if (status == Status.REQUIRED && reasons.isEmpty()) {
            throw new IllegalArgumentException("Required discovery must have a reason");
        }
    }

    public static DiscoveryDecision skipped() { return new DiscoveryDecision(Status.SKIPPED, List.of()); }

    public enum Status { REQUIRED, SKIPPED }
    public enum Reason {
        HIGH_SECURITY, INTEGRATION_TASK, LOW_SCOPE_CONFIDENCE, MULTI_MODULE,
        PRIMARY_DIRTY_OVERLAP, PROTECTED_OR_DATABASE_SCOPE, LARGE_PRIMARY_SCOPE
    }
}
