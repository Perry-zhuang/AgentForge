package io.agentforge.model;

import java.util.List;
import java.util.Set;

public record ChangeReport(
        RepositoryInfo repository,
        List<ChangedFile> files,
        Set<String> modules,
        boolean configChange,
        boolean dependencyChange,
        boolean databaseChange,
        boolean securityChange,
        boolean agentsChange,
        boolean controllerChange,
        List<String> warnings
) {
    public ChangeReport {
        files = List.copyOf(files);
        modules = Set.copyOf(modules);
        warnings = List.copyOf(warnings);
    }

    public long count(ChangeStatus status) {
        return files.stream().filter(file -> file.status() == status).count();
    }
}
