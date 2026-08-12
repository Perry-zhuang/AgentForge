package io.agentforge.model;

public record RepositoryReport(
        RepositoryInfo repository,
        ProjectTechnology technology,
        RepositoryStructure structure,
        ContextReport context,
        ArchitectureReport architecture
) {}
