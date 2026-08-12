package io.agentforge.model;

import java.util.List;
import java.util.Optional;

public record RelevantFile(
        String path,
        Optional<String> className,
        String module,
        int score,
        RelevanceLevel level,
        List<MatchEvidence> evidence
) {
    public RelevantFile {
        className = className == null ? Optional.empty() : className;
        evidence = List.copyOf(evidence);
    }
}
