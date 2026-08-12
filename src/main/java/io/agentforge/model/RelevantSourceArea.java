package io.agentforge.model;

import java.util.List;

public record RelevantSourceArea(String name, int score, List<String> relevantFiles) {
    public RelevantSourceArea {
        relevantFiles = List.copyOf(relevantFiles);
    }
}
