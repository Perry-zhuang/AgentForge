package io.agentforge.discovery;

import java.util.List;

public record DiscoveryValidation(Status status, List<String> reasons, List<String> frozenPrimary,
                                  List<String> readOnlyFiles) {
    public DiscoveryValidation {
        if (status == null || reasons == null || frozenPrimary == null || readOnlyFiles == null) {
            throw new IllegalArgumentException("Discovery validation facts are required");
        }
        reasons = List.copyOf(reasons);
        frozenPrimary = List.copyOf(frozenPrimary);
        readOnlyFiles = List.copyOf(readOnlyFiles);
    }
    public enum Status { APPROVED, REJECTED }
}
