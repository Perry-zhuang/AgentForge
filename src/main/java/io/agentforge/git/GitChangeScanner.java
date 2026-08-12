package io.agentforge.git;

import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.scanner.ScanSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        boolean config = false;
        boolean dependency = false;
        boolean database = false;
        boolean security = false;
        boolean agents = false;
        boolean controller = false;
        for (ChangedFile file : files) {
            changedModules.add(resolveModule(file.path(), moduleRoots));
            for (String path : paths(file)) {
                String lower = path.toLowerCase(Locale.ROOT);
                String base = lower.substring(lower.lastIndexOf('/') + 1);
                config |= lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")
                        || base.startsWith(".env") || lower.contains("/config/");
                dependency |= RepositoryScanner.isBuildDescriptor(base) || base.endsWith("lock")
                        || base.equals("yarn.lock") || base.equals("pnpm-lock.yaml");
                database |= lower.endsWith(".sql") || lower.contains("/migration/") || lower.startsWith("database/")
                        || lower.contains("/db/migration/");
                security |= lower.startsWith("security/") || lower.contains("/security/");
                agents |= base.equals("agents.md") || base.equals("claude.md") || lower.startsWith(".agentforge/");
                controller |= base.endsWith("controller.java") || lower.contains("/controller/");
            }
        }
        return new ChangeReport(repository, files, changedModules, config, dependency, database,
                security, agents, controller, repository.warnings());
    }

    List<ChangedFile> parsePorcelain(String output, String scope) {
        List<ChangedFile> files = new ArrayList<>();
        String[] tokens = output.split("\\u0000", -1);
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isEmpty() || token.length() < 3) continue;
            String status = token.substring(0, 2);
            String path = stripScope(token.substring(3), scope);
            boolean renamed = status.indexOf('R') >= 0 || status.indexOf('C') >= 0;
            String oldPath = null;
            if (renamed && index + 1 < tokens.length) {
                oldPath = stripScope(tokens[++index], scope);
            }
            ChangeStatus changeStatus = classifyStatus(status, renamed);
            files.add(new ChangedFile(changeStatus, path, oldPath));
        }
        files.sort(Comparator.comparing(ChangedFile::path));
        return files;
    }

    private ChangeStatus classifyStatus(String status, boolean renamed) {
        if ("??".equals(status)) return ChangeStatus.UNTRACKED;
        if (renamed) return ChangeStatus.RENAMED;
        if (status.indexOf('D') >= 0) return ChangeStatus.DELETED;
        if (status.indexOf('A') >= 0) return ChangeStatus.ADDED;
        return ChangeStatus.MODIFIED;
    }

    private String stripScope(String gitPath, String scope) {
        String normalized = gitPath.replace('\\', '/');
        if (".".equals(scope)) return normalized;
        String prefix = scope.endsWith("/") ? scope : scope + "/";
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
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
