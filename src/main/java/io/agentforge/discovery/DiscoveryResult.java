package io.agentforge.discovery;

import java.util.List;

public record DiscoveryResult(
        List<Finding> findings, List<RelevantFlow> relevantFlows, List<String> proposedPrimaryScope,
        List<String> proposedSecondaryScope, List<String> referenceFiles, List<String> risks,
        List<String> unknowns, Confidence confidence
) {
    public DiscoveryResult {
        if (findings == null || relevantFlows == null || proposedPrimaryScope == null
                || proposedSecondaryScope == null || referenceFiles == null || risks == null
                || unknowns == null || confidence == null) {
            throw new IllegalArgumentException("Discovery result fields are required");
        }
        findings = List.copyOf(findings);
        relevantFlows = List.copyOf(relevantFlows);
        proposedPrimaryScope = List.copyOf(proposedPrimaryScope);
        proposedSecondaryScope = List.copyOf(proposedSecondaryScope);
        referenceFiles = List.copyOf(referenceFiles);
        risks = List.copyOf(risks);
        unknowns = List.copyOf(unknowns);
    }

    public record Finding(String summary, List<String> evidence) {
        public Finding {
            if (summary == null || summary.isBlank() || evidence == null) {
                throw new IllegalArgumentException("Discovery finding is malformed");
            }
            evidence = List.copyOf(evidence);
        }
    }

    public record RelevantFlow(String description, List<String> files) {
        public RelevantFlow {
            if (description == null || description.isBlank() || files == null) {
                throw new IllegalArgumentException("Discovery flow is malformed");
            }
            files = List.copyOf(files);
        }
    }

    public enum Confidence { HIGH, MEDIUM, LOW }
}
