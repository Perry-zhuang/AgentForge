package io.agentforge.scanner;

import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.DatabaseTechnology;
import io.agentforge.model.FileType;
import io.agentforge.model.FrontendFramework;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryStructure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class RepositoryScanner {
    private final GitRepositoryInspector gitInspector;

    public RepositoryScanner(GitRepositoryInspector gitInspector) {
        this.gitInspector = gitInspector;
    }

    public Result scan(Path requestedPath) {
        Path root = validateDirectory(requestedPath);
        RepositoryInfo repository = gitInspector.inspect(root);
        ScanSupport.WalkResult walk = ScanSupport.walk(root);
        List<String> warnings = new ArrayList<>(walk.warnings());
        Map<FileType, Integer> counts = new EnumMap<>(FileType.class);
        Set<String> modules = new TreeSet<>();
        Set<BuildSystem> buildSystems = EnumSet.noneOf(BuildSystem.class);
        Set<BackendFramework> backend = EnumSet.noneOf(BackendFramework.class);
        Set<FrontendFramework> frontend = EnumSet.noneOf(FrontendFramework.class);
        Set<DatabaseTechnology> databases = EnumSet.noneOf(DatabaseTechnology.class);
        int sourceFiles = 0;
        int testFiles = 0;

        for (Path file : walk.files()) {
            String relative = ScanSupport.relative(root, file);
            String name = file.getFileName().toString();
            FileType type = classify(name);
            counts.merge(type, 1, Integer::sum);
            boolean source = isSource(type);
            if (source) {
                sourceFiles++;
                if (isTestPath(relative)) {
                    testFiles++;
                }
            }
            if (isBuildDescriptor(name)) {
                String module = ScanSupport.relative(root, file.getParent());
                modules.add(module.isBlank() ? "." : module);
            }
            if ("pom.xml".equals(name)) {
                buildSystems.add(BuildSystem.MAVEN);
            } else if ("build.gradle".equals(name) || "build.gradle.kts".equals(name)) {
                buildSystems.add(BuildSystem.GRADLE);
            } else if ("package.json".equals(name)) {
                buildSystems.add(BuildSystem.NODE);
            }

            inspectTechnologyEvidence(root, file, type, warnings, backend, frontend, databases);
        }
        if (modules.isEmpty() && sourceFiles > 0) modules.add(".");
        RepositoryStructure structure = new RepositoryStructure(
                new ArrayList<>(modules), counts, sourceFiles, testFiles, warnings);
        ProjectTechnology technology = new ProjectTechnology(buildSystems, backend, frontend, databases);
        return new Result(repository, technology, structure, walk.files());
    }

    private Path validateDirectory(Path requestedPath) {
        Path root = requestedPath.toAbsolutePath().normalize();
        if (!Files.exists(root)) throw new IllegalArgumentException("Path does not exist: " + root);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Path is not a directory: " + root);
        return GitRepositoryInspector.realPath(root);
    }

    private void inspectTechnologyEvidence(Path root, Path file, FileType type, List<String> warnings,
                                           Set<BackendFramework> backend, Set<FrontendFramework> frontend,
                                           Set<DatabaseTechnology> databases) {
        String name = file.getFileName().toString();
        boolean descriptor = isBuildDescriptor(name);
        boolean configuration = type == FileType.YAML || name.endsWith(".properties");
        boolean springSource = type == FileType.JAVA || type == FileType.KOTLIN;
        if (!descriptor && !configuration && !springSource) return;
        String text = ScanSupport.readText(root, file, warnings).toLowerCase(Locale.ROOT);
        if (text.contains("spring-boot") || text.contains("springframework.boot")
                || (springSource && text.contains("org.springframework"))) backend.add(BackendFramework.SPRING_BOOT);
        if (descriptor && (text.contains("\"vue\"") || text.contains("'vue'"))) frontend.add(FrontendFramework.VUE);
        if (descriptor && (text.contains("\"react\"") || text.contains("'react'"))) frontend.add(FrontendFramework.REACT);
        if (descriptor || configuration) {
            if (text.contains("mysql") || text.contains("jdbc:mysql")) databases.add(DatabaseTechnology.MYSQL);
            if (text.contains("postgresql") || text.contains("jdbc:postgres")) databases.add(DatabaseTechnology.POSTGRESQL);
            if (text.contains("jdbc:h2") || text.contains("com.h2database")) databases.add(DatabaseTechnology.H2);
            if (text.contains("sqlite") || text.contains("jdbc:sqlite")) databases.add(DatabaseTechnology.SQLITE);
        }
    }

    public static boolean isBuildDescriptor(String name) {
        return "pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name) || "package.json".equals(name);
    }

    private boolean isSource(FileType type) {
        return type.source();
    }

    private boolean isTestPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.startsWith("test/") || lower.contains("/__tests__/")
                || lower.endsWith("test.java") || lower.endsWith("tests.java") || lower.matches(".*\\.(test|spec)\\.[jt]sx?$");
    }

    public static FileType classify(String name) {
        return FileType.classify(name);
    }

    public record Result(
            RepositoryInfo repository,
            ProjectTechnology technology,
            RepositoryStructure structure,
            List<Path> files
    ) {
        public Result {
            files = List.copyOf(files);
        }
    }
}
