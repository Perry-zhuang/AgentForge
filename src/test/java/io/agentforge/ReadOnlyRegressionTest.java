package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyRegressionTest {
    @Test
    void promptLeavesTargetRepositoryBranchHeadStatusAndFilesUnchanged(@TempDir Path root) throws Exception {
        baseline(root);
        String branch = git(root, "branch", "--show-current");
        String head = git(root, "rev-parse", "HEAD");
        String status = git(root, "status", "--porcelain=v1", "--untracked-files=all");
        Map<String, String> hashes = hashes(root, null);

        CliTestHarness.Result result = CliTestHarness.execute("prompt", root.toString(), "分析登录流程");

        assertThat(result.exitCode()).isZero();
        assertThat(git(root, "branch", "--show-current")).isEqualTo(branch);
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(git(root, "status", "--porcelain=v1", "--untracked-files=all")).isEqualTo(status);
        assertThat(hashes(root, null)).isEqualTo(hashes);
        assertThat(root.resolve(".agentforge")).doesNotExist();
    }

    @Test
    void repositoryOutputChangesOnlyTheExplicitlyRequestedFile(@TempDir Path root) throws Exception {
        baseline(root);
        Path output = root.resolve("task-prompt.md");
        String branch = git(root, "branch", "--show-current");
        String head = git(root, "rev-parse", "HEAD");
        Map<String, String> hashes = hashes(root, output);

        CliTestHarness.Result result = CliTestHarness.execute(
                "prompt", root.toString(), "分析登录流程", "--output", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("WARNING: Prompt output is being written inside the target repository.");
        assertThat(output).exists();
        assertThat(git(root, "branch", "--show-current")).isEqualTo(branch);
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(git(root, "status", "--porcelain=v1", "--untracked-files=all")).isEqualTo("?? task-prompt.md");
        assertThat(hashes(root, output)).isEqualTo(hashes);
    }

    private static void baseline(Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "src/main/java/demo/LoginService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class LoginService { // 登录 流程
                }
                """);
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
    }

    private static Map<String, String> hashes(Path root, Path excluded) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (file.startsWith(root.resolve(".git")) || file.equals(excluded)) continue;
                result.put(root.relativize(file).toString(), hash(file));
            }
        }
        return result;
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(new String(output)).isZero();
        return new String(output).strip();
    }
}
