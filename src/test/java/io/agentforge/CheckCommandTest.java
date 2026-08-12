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

class CheckCommandTest {
    @Test
    void noChangesPassesAndArchitectureIsNotConfigured(@TempDir Path root) throws Exception {
        repository(root, "LoginService");

        CliTestHarness.Result result = CliTestHarness.execute("check", root.toString(), "修复登录异常");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "AgentForge Check",
                "Task Type                BUGFIX",
                "Overall                  PASS",
                "Baseline                 CURRENT_WORKING_TREE",
                "Status                   NOT CONFIGURED",
                "CHECK PASS");
    }

    @Test
    void secondaryModificationWarnsAndReturnsZeroDeterministically(@TempDir Path root) throws Exception {
        repository(root, "ALoginService", "BLoginService", "CLoginService", "ZLoginService");
        append(root.resolve("src/main/java/demo/ZLoginService.java"), "// changed\n");

        CliTestHarness.Result first = CliTestHarness.execute("check", root.toString(), "修复登录异常");
        CliTestHarness.Result second = CliTestHarness.execute("check", root.toString(), "修复登录异常");
        CliTestHarness.Result third = CliTestHarness.execute("check", root.toString(), "修复登录异常");

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).contains("SECONDARY_SCOPE_MODIFIED", "CHECK WARNING");
        assertThat(first.output()).isEqualTo(second.output()).isEqualTo(third.output());
        assertThat(hash(first.output())).isEqualTo(hash(second.output())).isEqualTo(hash(third.output()));
    }

    @Test
    void analysisOutOfScopeSourceReportsBothDimensionsAndReturnsOne(@TempDir Path root) throws Exception {
        repository(root, "LoginService");
        append(root.resolve("src/main/java/demo/LoginService.java"), "// changed\n");
        append(root.resolve("README.md"), "changed\n");

        CliTestHarness.Result result = CliTestHarness.execute("check", root.toString(), "分析登录流程");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(
                "OUT_OF_SCOPE_MODIFICATION README.md",
                "ANALYSIS_SOURCE_MODIFICATION src/main/java/demo/LoginService.java",
                "CHECK FAIL");
    }

    @Test
    void dirtyRepositoryCheckIsReadOnlyForGitAndEveryRepositoryFile(@TempDir Path root) throws Exception {
        repository(root, "LoginService");
        append(root.resolve("src/main/java/demo/LoginService.java"), "// existing dirty work\n");
        String branchBefore = gitOutput(root, "branch", "--show-current");
        String headBefore = gitOutput(root, "rev-parse", "HEAD");
        String statusBefore = gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all");
        Map<String, String> filesBefore = fileHashes(root);

        CliTestHarness.Result result = CliTestHarness.execute("check", root.toString(), "修复登录异常");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "Plan Baseline            DIRTY",
                "Execution Attribution    NOT AVAILABLE",
                "Check evaluates the current working tree.",
                "Pre-existing dirty changes cannot yet be distinguished");
        assertThat(gitOutput(root, "branch", "--show-current")).isEqualTo(branchBefore);
        assertThat(gitOutput(root, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all"))
                .isEqualTo(statusBefore);
        assertThat(fileHashes(root)).isEqualTo(filesBefore);
    }

    @Test
    void invalidPathAndMissingTaskReturnTwo() {
        CliTestHarness.Result invalidPath = CliTestHarness.execute(
                "check", "missing-check-repository", "修复登录异常");
        CliTestHarness.Result missingTask = CliTestHarness.execute("check");

        assertThat(invalidPath.exitCode()).isEqualTo(2);
        assertThat(invalidPath.output()).contains("ERROR Path does not exist");
        assertThat(missingTask.exitCode()).isEqualTo(2);
        assertThat(missingTask.output()).contains("Missing required parameter");
    }

    private static void repository(Path root, String... classNames) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "README.md", "# Demo\n");
        for (String className : classNames) {
            write(root, "src/main/java/demo/" + className + ".java", """
                    package demo;
                    import org.springframework.stereotype.Service;
                    @Service class %s { // 登录 异常 流程
                    }
                    """.formatted(className));
        }
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
    }

    private static Map<String, String> fileHashes(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git")))
                    .sorted().toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }

    private static void append(Path file, String content) throws Exception {
        Files.writeString(file, Files.readString(file) + content);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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
