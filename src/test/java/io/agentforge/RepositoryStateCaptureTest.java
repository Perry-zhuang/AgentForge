package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.git.GitClient;
import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.RepositorySnapshot.FileKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryStateCaptureTest {
    @Test
    void capturesCompleteTrackedAndNonIgnoredUntrackedStateDeterministicallyAndReadOnly(@TempDir Path temp)
            throws Exception {
        Path root = temp.resolve("repo");
        Files.createDirectories(root);
        initialize(root);
        write(root, ".gitignore", "*.log\n");
        write(root, "tracked.txt", "tracked\n");
        write(root, "dirty.txt", "before\n");
        Files.write(root.resolve("binary.bin"), new byte[]{0, 1, 2, (byte) 0xff});
        Files.createSymbolicLink(root.resolve("tracked-link"), Path.of("tracked.txt"));
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        write(root, "dirty.txt", "after\n");
        write(root, "untracked.txt", "untracked\n");
        write(root, "ignored.log", "ignored\n");

        String branchBefore = gitOutput(root, "branch", "--show-current");
        String headBefore = gitOutput(root, "rev-parse", "HEAD");
        String statusBefore = gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all");
        Map<String, String> hashesBefore = fileHashes(root);
        RepositoryStateCapture capture = capture();

        var first = capture.capture(root);
        var second = capture.capture(root);
        var third = capture.capture(root);

        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(first.files()).extracting(file -> file.path()).contains(
                ".gitignore", "tracked.txt", "dirty.txt", "binary.bin", "tracked-link", "untracked.txt")
                .doesNotContain("ignored.log");
        assertThat(first.files()).filteredOn(file -> file.path().equals("tracked.txt")).singleElement()
                .satisfies(file -> {
                    assertThat(file.porcelainStatus()).isEqualTo("  ");
                    assertThat(file.tracked()).isTrue();
                    assertThat(file.worktreeContentIdentity()).hasSize(64);
                    assertThat(file.indexIdentity()).isNotBlank();
                });
        assertThat(first.files()).filteredOn(file -> file.path().equals("dirty.txt")).singleElement()
                .satisfies(file -> assertThat(file.porcelainStatus()).isEqualTo(" M"));
        assertThat(first.files()).filteredOn(file -> file.path().equals("untracked.txt")).singleElement()
                .satisfies(file -> {
                    assertThat(file.tracked()).isFalse();
                    assertThat(file.porcelainStatus()).isEqualTo("??");
                    assertThat(file.indexIdentity()).isNull();
                });
        assertThat(first.files()).filteredOn(file -> file.path().equals("binary.bin")).singleElement()
                .satisfies(file -> assertThat(file.worktreeContentIdentity()).hasSize(64));
        assertThat(first.files()).filteredOn(file -> file.path().equals("tracked-link")).singleElement()
                .satisfies(file -> {
                    assertThat(file.kind()).isEqualTo(FileKind.SYMLINK);
                    assertThat(file.worktreeContentIdentity()).hasSize(64);
                });

        RepositorySnapshotStore store = new RepositorySnapshotStore();
        Path json1 = temp.resolve("baseline-1.json");
        Path json2 = temp.resolve("baseline-2.json");
        Path json3 = temp.resolve("baseline-3.json");
        store.write(first, json1, false);
        store.write(second, json2, false);
        store.write(third, json3, false);
        assertThat(Files.readAllBytes(json1)).isEqualTo(Files.readAllBytes(json2))
                .isEqualTo(Files.readAllBytes(json3));
        assertThat(store.read(json1)).isEqualTo(first);

        assertThat(gitOutput(root, "branch", "--show-current")).isEqualTo(branchBefore);
        assertThat(gitOutput(root, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all"))
                .isEqualTo(statusBefore);
        assertThat(fileHashes(root)).isEqualTo(hashesBefore);
    }

    @Test
    void keepsWorktreeIndexAndPorcelainIdentitiesIndependentAcrossStaging(@TempDir Path root) throws Exception {
        initialize(root);
        write(root, "Foo.java", "before\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        write(root, "Foo.java", "after\n");
        RepositoryStateCapture capture = capture();

        var unstaged = capture.capture(root).files().get(0);
        git(root, "add", "Foo.java");
        var staged = capture.capture(root).files().get(0);

        assertThat(unstaged.worktreeContentIdentity()).isEqualTo(staged.worktreeContentIdentity());
        assertThat(unstaged.indexIdentity()).isNotEqualTo(staged.indexIdentity());
        assertThat(unstaged.porcelainStatus()).isEqualTo(" M");
        assertThat(staged.porcelainStatus()).isEqualTo("M ");
    }

    @Test
    void gitlinkUsesOnlyParentRepositoryFactsAndNeverListsSubmoduleContents(@TempDir Path temp) throws Exception {
        Path child = temp.resolve("child");
        Files.createDirectories(child);
        initialize(child);
        write(child, "nested.bin", "child\n");
        git(child, "add", ".");
        git(child, "commit", "-m", "child");

        Path parent = temp.resolve("parent");
        Files.createDirectories(parent);
        initialize(parent);
        write(parent, "README.md", "parent\n");
        git(parent, "add", ".");
        git(parent, "commit", "-m", "parent");
        git(parent, "-c", "protocol.file.allow=always", "submodule", "add", child.toString(), "module");
        git(parent, "commit", "-am", "add submodule");
        write(parent.resolve("module"), "nested.bin", "changed inside submodule\n");

        var snapshot = capture().capture(parent);

        assertThat(snapshot.files()).filteredOn(file -> file.path().equals("module")).singleElement()
                .satisfies(file -> {
                    assertThat(file.kind()).isEqualTo(FileKind.GITLINK);
                    assertThat(file.worktreeContentIdentity()).isNull();
                    assertThat(file.indexIdentity()).startsWith("160000:");
                });
        assertThat(snapshot.files()).extracting(file -> file.path()).doesNotContain("module/nested.bin");
    }

    private RepositoryStateCapture capture() {
        GitClient git = new GitClient();
        return new RepositoryStateCapture(git, new GitRepositoryInspector(git));
    }

    private static void initialize(Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Map<String, String> fileHashes(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git"))).sorted().toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(new String(output)).isZero();
        return new String(output);
    }

    private static void git(Path root, String... args) throws Exception {
        gitOutput(root, args);
    }
}
