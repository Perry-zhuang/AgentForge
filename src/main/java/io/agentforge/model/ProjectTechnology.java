package io.agentforge.model;

import java.util.Set;

public record ProjectTechnology(
        Set<BuildSystem> buildSystems,
        Set<BackendFramework> backendFrameworks,
        Set<FrontendFramework> frontendFrameworks,
        Set<DatabaseTechnology> databases
) {
    public ProjectTechnology {
        buildSystems = Set.copyOf(buildSystems);
        backendFrameworks = Set.copyOf(backendFrameworks);
        frontendFrameworks = Set.copyOf(frontendFrameworks);
        databases = Set.copyOf(databases);
    }
}
