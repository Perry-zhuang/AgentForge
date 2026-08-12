package io.agentforge.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryInspectorTest {
    private final GitRepositoryInspector inspector = new GitRepositoryInspector(new GitClient());

    @Test
    void reportsOrdinaryDirectoryAsNonGit(@TempDir Path root) {
        var repository = inspector.inspect(root);

        assertThat(repository.gitRepository()).isFalse();
        assertThat(repository.branch()).isEqualTo("N/A");
        assertThat(repository.head()).isEqualTo("N/A");
    }

    @Test
    void handlesUnbornHeadAndReportsWorkingTreeChanges(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "README.md", "demo");
        write(root, "src/main/java/Demo.java", "class Demo {}");

        var repository = inspector.inspect(root);

        assertThat(repository.gitRepository()).isTrue();
        assertThat(repository.branch()).isEqualTo("main");
        assertThat(repository.head()).isEqualTo("UNBORN");
        assertThat(repository.dirty()).isTrue();
    }

    @Test
    void reportsActualCommitAndDetachedBranchAfterTwoCommits(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        configureIdentity(root);
        write(root, "README.md", "A");
        git(root, "add", ".");
        git(root, "commit", "-m", "commit A");
        String commitA = gitOutput(root, "rev-parse", "HEAD").strip();
        write(root, "README.md", "B");
        git(root, "commit", "-am", "commit B");
        git(root, "checkout", "--detach", commitA);

        var repository = inspector.inspect(root);

        assertThat(repository.branch()).isEqualTo("DETACHED");
        assertThat(repository.head()).isEqualTo(commitA);
    }

    static void configureIdentity(Path root) throws Exception {
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
    }

    static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String gitOutput(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(new String(output)).isZero();
        return new String(output);
    }

    static void git(Path root, String... args) throws Exception {
        gitOutput(root, args);
    }
}
