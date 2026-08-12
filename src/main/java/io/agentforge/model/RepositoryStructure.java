package io.agentforge.model;

import java.util.List;
import java.util.Map;

public record RepositoryStructure(
        List<String> modules,
        Map<FileType, Integer> fileCounts,
        int sourceFiles,
        int testFiles,
        List<String> warnings
) {
    public RepositoryStructure {
        modules = List.copyOf(modules);
        fileCounts = Map.copyOf(fileCounts);
        warnings = List.copyOf(warnings);
    }

    public int count(FileType type) {
        return fileCounts.getOrDefault(type, 0);
    }
}
