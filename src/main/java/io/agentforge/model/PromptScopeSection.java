package io.agentforge.model;

import java.util.List;

public record PromptScopeSection(
        String heading,
        String definition,
        List<PromptScopeFile> files,
        List<String> instructions
) {
    public PromptScopeSection {
        if (heading == null || heading.isBlank()) throw new IllegalArgumentException("Prompt scope heading is required");
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("Prompt scope definition is required");
        }
        files = List.copyOf(files);
        instructions = List.copyOf(instructions);
    }
}
