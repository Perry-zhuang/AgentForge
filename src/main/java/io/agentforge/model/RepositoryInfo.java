package io.agentforge.model;

import java.nio.file.Path;
import java.util.List;

public record RepositoryInfo(
        Path path,
        boolean gitRepository,
        Path gitRoot,
        String branch,
        String head,
        boolean dirty,
        List<String> warnings
) {
    public RepositoryInfo {
        warnings = List.copyOf(warnings);
    }
}
