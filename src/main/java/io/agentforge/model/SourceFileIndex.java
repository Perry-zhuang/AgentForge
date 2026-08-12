package io.agentforge.model;

import java.util.Set;

public record SourceFileIndex(
        String path,
        FileType fileType,
        String qualifiedName,
        String className,
        String packageName,
        JavaRole role,
        Set<String> imports,
        Set<String> dependencyTypes,
        String content
) {
    public SourceFileIndex {
        imports = Set.copyOf(imports);
        dependencyTypes = Set.copyOf(dependencyTypes);
    }

    public boolean javaSource() {
        return fileType == FileType.JAVA;
    }
}
