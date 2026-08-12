package io.agentforge.model;

import java.util.List;

public record RelevantModule(String path, int score, List<String> relevantFiles) {
    public RelevantModule {
        relevantFiles = List.copyOf(relevantFiles);
    }
}
