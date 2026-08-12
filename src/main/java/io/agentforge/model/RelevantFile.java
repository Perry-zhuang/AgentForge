package io.agentforge.model;

import java.util.List;
import java.util.Optional;

public record RelevantFile(
        String path,
        Optional<String> className,
        String module,
        FileResponsibility responsibility,
        int score,
        ConfidenceProfile confidence,
        List<String> matchedCoreTokens,
        List<String> missingCoreTokens,
        List<MatchEvidence> evidence
) {
    public RelevantFile {
        className = className == null ? Optional.empty() : className;
        if (responsibility == null) throw new IllegalArgumentException("File responsibility is required");
        if (confidence == null) throw new IllegalArgumentException("Confidence profile is required");
        matchedCoreTokens = List.copyOf(matchedCoreTokens);
        missingCoreTokens = List.copyOf(missingCoreTokens);
        evidence = List.copyOf(evidence);
    }

    public RelevanceLevel level() {
        return confidence.level();
    }

    public int totalCoreTokens() {
        return matchedCoreTokens.size() + missingCoreTokens.size();
    }
}
