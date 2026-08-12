package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptCommandTest {
    @Test
    void promptsCurrentDirectoryWhenOnlyTaskIsProvided() {
        CliTestHarness.Result result = CliTestHarness.execute("prompt", "分析 PlanService 流程");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).startsWith("# Engineering Task").contains(
                "Task: 分析 PlanService 流程", "Task Type: ANALYSIS");
    }

    @Test
    void promptsExplicitRepositoryToStandardOutput(@TempDir Path root) throws Exception {
        repository(root);

        CliTestHarness.Result result = CliTestHarness.execute("prompt", root.toString(), "分析登录流程");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "# Engineering Task", "Task Type: ANALYSIS", "LoginService.java", "# Final Report");
        assertThat(root.resolve(".agentforge")).doesNotExist();
    }

    @Test
    void writesUtf8OutputRejectsExistingFileAndRequiresForceToOverwrite(@TempDir Path root) throws Exception {
        repository(root);
        Path output = root.getParent().resolve(root.getFileName() + "-prompt.md");

        CliTestHarness.Result first = CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString());
        String firstContent = Files.readString(output, StandardCharsets.UTF_8);
        CliTestHarness.Result refused = CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString());
        CliTestHarness.Result forced = CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString(), "--force");

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).isEmpty();
        assertThat(firstContent).contains("Task: 修复登录异常", "Task Type: BUGFIX");
        assertThat(refused.exitCode()).isEqualTo(2);
        assertThat(refused.output()).contains("Output file already exists", "--force");
        assertThat(forced.exitCode()).isZero();
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(firstContent);
    }

    @Test
    void resolvesRelativeOutputFromTheCurrentWorkingDirectory(@TempDir Path root) throws Exception {
        repository(root);
        Path output = root.getParent().resolve(root.getFileName() + "-relative.md").toAbsolutePath().normalize();
        Path current = Path.of("").toAbsolutePath().normalize();
        Path relative = current.relativize(output);

        CliTestHarness.Result result = CliTestHarness.execute(
                "prompt", root.toString(), "分析登录流程", "--output", relative.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(output).exists();
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains("Task: 分析登录流程");
    }

    @Test
    void warnsOnStderrWhenOutputIsInsideRepositoryWithoutPollutingPrompt(@TempDir Path root) throws Exception {
        repository(root);
        Path output = root.resolve("task-prompt.md");

        CliTestHarness.Result result = CliTestHarness.execute(
                "prompt", root.toString(), "分析登录流程", "--output", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "WARNING: Prompt output is being written inside the target repository.");
        assertThat(Files.readString(output)).doesNotContain("WARNING:");
    }

    @Test
    void producesSameFileHashAcrossCreateAndTwoForcedWrites(@TempDir Path root) throws Exception {
        repository(root);
        Path output = root.getParent().resolve(root.getFileName() + "-stable.md");

        assertThat(CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString()).exitCode()).isZero();
        String first = hash(output);
        assertThat(CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString(), "--force").exitCode()).isZero();
        String second = hash(output);
        assertThat(CliTestHarness.execute(
                "prompt", root.toString(), "修复登录异常", "--output", output.toString(), "--force").exitCode()).isZero();
        String third = hash(output);

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    void returnsTwoForInvalidArgumentsPathsConfigurationAndOutput(@TempDir Path root) throws Exception {
        repository(root);
        Path invalidConfig = root.resolve("invalid-config");
        repository(invalidConfig);
        write(invalidConfig, ".agentforge/policies.yaml", "schema_version: 1\ninvalid: true\n");

        assertThat(CliTestHarness.execute("prompt").exitCode()).isEqualTo(2);
        assertThat(CliTestHarness.execute("prompt", "missing-prompt-repository", "分析登录流程").exitCode())
                .isEqualTo(2);
        assertThat(CliTestHarness.execute("prompt", invalidConfig.toString(), "分析登录流程").exitCode())
                .isEqualTo(2);
        assertThat(CliTestHarness.execute("prompt", root.toString(), "分析登录流程", "--force").exitCode())
                .isEqualTo(2);
        CliTestHarness.Result missingParent = CliTestHarness.execute(
                "prompt", root.toString(), "分析登录流程", "--output",
                root.resolve("missing/output.md").toString());
        assertThat(missingParent.exitCode()).isEqualTo(2);
        assertThat(missingParent.output()).contains("Output parent directory does not exist");
    }

    private static void repository(Path root) throws Exception {
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "src/main/java/demo/LoginService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class LoginService { // 登录 流程 异常
                }
                """);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
