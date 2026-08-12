package io.agentforge.git;

import io.agentforge.model.RepositoryInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GitRepositoryInspector {
    private final GitClient git;

    public GitRepositoryInspector(GitClient git) {
        this.git = git;
    }

    public RepositoryInfo inspect(Path requestedPath) {
        Path path = realPath(requestedPath);
        List<String> warnings = new ArrayList<>();
        GitClient.Result rootResult = git.run(path, "rev-parse", "--show-toplevel");
        if (!rootResult.succeeded()) {
            return new RepositoryInfo(path, false, null, "N/A", "N/A", false, warnings);
        }

        Path gitRoot = Path.of(rootResult.trimmedOutput()).toAbsolutePath().normalize();
        GitClient.Result branchResult = git.run(path, "symbolic-ref", "--quiet", "--short", "HEAD");
        String branch = branchResult.succeeded() && !branchResult.trimmedOutput().isBlank()
                ? branchResult.trimmedOutput() : "DETACHED";
        GitClient.Result headResult = git.run(path, "rev-parse", "--verify", "HEAD");
        String head = headResult.succeeded() ? headResult.trimmedOutput() : "UNBORN";
        if ("DETACHED".equals(branch) && "UNBORN".equals(head)) {
            GitClient.Result unbornBranch = git.run(path, "branch", "--show-current");
            branch = unbornBranch.trimmedOutput().isBlank() ? "UNBORN" : unbornBranch.trimmedOutput();
        }
        String pathSpec = scopePath(gitRoot, path);
        GitClient.Result status = git.run(gitRoot, "status", "--porcelain=v1", "--untracked-files=all", "--", pathSpec);
        if (!status.succeeded()) {
            warnings.add("Git status failed: " + status.error());
        }
        return new RepositoryInfo(path, true, gitRoot, branch, head, status.succeeded() && !status.output().isBlank(), warnings);
    }

    public static String scopePath(Path gitRoot, Path target) {
        String relative = gitRoot.relativize(target).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    public static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }
}
