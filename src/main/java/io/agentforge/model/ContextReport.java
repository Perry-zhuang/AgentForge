package io.agentforge.model;

import java.util.List;
import java.util.Map;

public record ContextReport(
        boolean agentsPresent,
        int agentsLines,
        boolean readmePresent,
        boolean architectureDocumentPresent,
        boolean docsDirectoryPresent,
        int skillsCount,
        int agentRulesCount,
        boolean gitRulesPresent,
        boolean databaseRulesPresent,
        boolean apiRulesPresent,
        boolean testingRulesPresent,
        Map<String, List<String>> evidence,
        List<String> warnings
) {
    public ContextReport {
        evidence = Map.copyOf(evidence);
        warnings = List.copyOf(warnings);
    }
}
