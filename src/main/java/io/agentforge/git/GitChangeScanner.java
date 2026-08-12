package io.agentforge.git;

import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.scanner.ScanSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GitChangeScanner {
    private final GitClient git;
    private final GitRepositoryInspector repositoryInspector;

    public GitChangeScanner(GitClient git, GitRepositoryInspector repositoryInspector) {
        this.git = git;
        this.repositoryInspector = repositoryInspector;
    }

    public ChangeReport scan(Path requestedPath) {
        Path target = GitRepositoryInspector.realPath(requestedPath);
        RepositoryInfo repository = repositoryInspector.inspect(target);
        if (!repository.gitRepository()) {
            throw new IllegalArgumentException("Not a Git repository: " + target);
        }
        String scope = GitRepositoryInspector.scopePath(repository.gitRoot(), target);
        GitClient.Result result = git.statusPorcelain(repository.gitRoot(), scope);
        if (!result.succeeded()) throw new IllegalStateException("Git status failed: " + result.error());

        List<ChangedFile> files = parsePorcelain(result.output(), scope);
        Set<String> moduleRoots = findModuleRoots(target);
        Set<String> changedModules = new LinkedHashSet<>();
        List<String> changedPaths = new ArrayList<>();
        for (ChangedFile file : files) {
            changedModules.add(resolveModule(file.path(), moduleRoots));
            changedPaths.addAll(paths(file));
        }
        ChangeSignalClassifier.Signals signals = ChangeSignalClassifier.classify(changedPaths);
        return new ChangeReport(repository, files, changedModules, signals.config(), signals.dependency(),
                signals.database(), signals.security(), signals.agents(), signals.controller(), repository.warnings());
    }

    List<ChangedFile> parsePorcelain(String output, String scope) {
        return GitStatusParser.parse(output, scope).stream()
                .map(entry -> new ChangedFile(GitStatusParser.collapse(entry.porcelainStatus()),
                        entry.path(), entry.oldPath()))
                .toList();
    }

    private Set<String> findModuleRoots(Path target) {
        Set<String> modules = new LinkedHashSet<>();
        for (Path file : ScanSupport.walk(target).files()) {
            if (RepositoryScanner.isBuildDescriptor(file.getFileName().toString())) {
                String relative = ScanSupport.relative(target, file.getParent());
                modules.add(relative.isBlank() ? "." : relative);
            }
        }
        if (modules.isEmpty()) modules.add(".");
        return modules;
    }

    private String resolveModule(String path, Set<String> modules) {
        return modules.stream()
                .filter(module -> ".".equals(module) || path.equals(module) || path.startsWith(module + "/"))
                .max(Comparator.comparingInt(String::length))
                .orElseGet(() -> path.contains("/") ? path.substring(0, path.indexOf('/')) : ".");
    }

    private List<String> paths(ChangedFile file) {
        return file.oldPath() == null ? List.of(file.path()) : List.of(file.path(), file.oldPath());
    }
}
