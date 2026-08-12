package io.agentforge.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ChangeStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitChangeScannerTest {
    @Test
    void scansUntrackedFilesInUnbornRepository(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "README.md", "demo");
        write(root, "src/main/java/Demo.java", "class Demo {}");

        GitClient client = new GitClient();
        var report = new GitChangeScanner(client, new GitRepositoryInspector(client)).scan(root);

        assertThat(report.repository().head()).isEqualTo("UNBORN");
        assertThat(report.files()).extracting(file -> file.status()).containsOnly(ChangeStatus.UNTRACKED);
        assertThat(report.files()).extracting(file -> file.path()).containsExactly("README.md", "src/main/java/Demo.java");
    }

    @Test
    void scansStagedUnstagedUntrackedDeletedRenamedAndSpaces(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "staged.txt", "base");
        write(root, "modified.txt", "base");
        write(root, "deleted.txt", "base");
        write(root, "old name.txt", "base");
        git(root, "add", ".");
        git(root, "commit", "-m", "base");

        write(root, "staged.txt", "staged");
        git(root, "add", "staged.txt");
        write(root, "modified.txt", "modified");
        Files.delete(root.resolve("deleted.txt"));
        git(root, "mv", "old name.txt", "new name.txt");
        write(root, "untracked space.txt", "new");

        GitClient client = new GitClient();
        var report = new GitChangeScanner(client, new GitRepositoryInspector(client)).scan(root);

        assertThat(report.files()).extracting(file -> file.status()).contains(
                ChangeStatus.MODIFIED, ChangeStatus.DELETED, ChangeStatus.RENAMED, ChangeStatus.UNTRACKED);
        assertThat(report.files()).extracting(file -> file.path()).contains("staged.txt", "modified.txt", "deleted.txt", "new name.txt", "untracked space.txt");
        assertThat(report.files().stream().filter(file -> file.status() == ChangeStatus.RENAMED).findFirst().orElseThrow().oldPath())
                .isEqualTo("old name.txt");
    }

    @Test
    void scopesChangesToRequestedSubdirectory(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "inside/file.txt", "inside");
        write(root, "outside.txt", "outside");

        GitClient client = new GitClient();
        var report = new GitChangeScanner(client, new GitRepositoryInspector(client)).scan(root.resolve("inside"));

        assertThat(report.files()).extracting(file -> file.path()).containsExactly("file.txt");
    }

    @Test
    void detectsAllConfiguredSensitiveChangeSignals(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "pom.xml", "<project/>");
        write(root, "frontend/package.json", "{}");
        write(root, "src/main/resources/db/migration/V1.sql", "select 1;");
        write(root, "src/main/java/demo/security/Auth.java", "class Auth {}");
        write(root, "AGENTS.md", "# Rules");
        write(root, "src/main/resources/config/application.yaml", "demo: true");

        GitClient client = new GitClient();
        var report = new GitChangeScanner(client, new GitRepositoryInspector(client)).scan(root);

        assertThat(report.dependencyChange()).isTrue();
        assertThat(report.databaseChange()).isTrue();
        assertThat(report.securityChange()).isTrue();
        assertThat(report.agentsChange()).isTrue();
        assertThat(report.configChange()).isTrue();
    }

    @Test
    void renameOldAndNewPathsBothContributeSensitiveSignals(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "src/main/java/demo/security/Auth.java", "class Auth {}");
        write(root, "module/pom.xml", "<project/>");
        git(root, "add", ".");
        git(root, "commit", "-m", "base");
        Files.createDirectories(root.resolve("src/main/java/demo/common"));
        git(root, "mv", "src/main/java/demo/security/Auth.java", "src/main/java/demo/common/Auth.java");
        git(root, "mv", "module/pom.xml", "module/build.xml");

        GitClient client = new GitClient();
        var report = new GitChangeScanner(client, new GitRepositoryInspector(client)).scan(root);

        assertThat(report.securityChange()).isTrue();
        assertThat(report.dependencyChange()).isTrue();
        assertThat(report.files()).filteredOn(file -> file.status() == ChangeStatus.RENAMED)
                .extracting(file -> file.oldPath())
                .contains("src/main/java/demo/security/Auth.java", "module/pom.xml");
    }

    @Test
    void sharedPorcelainParserPreservesIndexWorktreeAndRenameFactsIndependently() {
        String output = " M unstaged.txt\0M  staged.txt\0R  new.txt\0old.txt\0?? untracked.txt\0";

        var entries = GitStatusParser.parse(output, ".");

        assertThat(entries).extracting(entry -> entry.porcelainStatus() + ":" + entry.path())
                .containsExactly("R :new.txt", "M :staged.txt", " M:unstaged.txt", "??:untracked.txt");
        assertThat(entries).filteredOn(entry -> entry.path().equals("new.txt")).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.oldPath()).isEqualTo("old.txt");
                    assertThat(entry.rename()).isTrue();
                });
    }

    @Test
    void gitClientDrainsLargeOutputWithoutPipeBufferTimeout(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        for (int index = 0; index < 1800; index++) {
            write(root, "generated/long-untracked-file-name-for-baseline-capture-" + index + ".txt", "x");
        }

        GitClient.Result result = new GitClient().run(root,
                "ls-files", "-z", "--others", "--exclude-standard");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output().length()).isGreaterThan(64 * 1024);
        assertThat(result.output()).contains("long-untracked-file-name-for-baseline-capture-1799.txt");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        assertThat(process.waitFor()).as(new String(process.getInputStream().readAllBytes())).isZero();
    }
}
