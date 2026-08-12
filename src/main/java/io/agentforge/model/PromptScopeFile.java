package io.agentforge.model;

public record PromptScopeFile(String name, String path) {
    public PromptScopeFile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Prompt scope file name is required");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Prompt scope file path is required");
    }
}
