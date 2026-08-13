package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanCommandTest {
    @Test
    void plansExplicitNonGitPathWithoutWritingToRepository(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        Path source = write(root, "src/main/java/demo/HistoryAlertService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class HistoryAlertService { // 历史 告警 流程
                }
                """);
        String before = Files.readString(source);

        CliTestHarness.Result result = CliTestHarness.execute(
                "plan", root.toString(), "分析历史告警流程");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "AgentForge Engineering Plan",
                "Type                     ANALYSIS",
                "Type Signal              ANALYSIS",
                "Type Confidence          HIGH",
                "Mutation Mode            READ_ONLY",
                "Security Impact          NONE",
                "Classification Source    DETECTED",
                "Plan Confidence          LOW",
                "Working Tree             NOT_AVAILABLE",
                "PRIMARY",
                "HistoryAlertService.java",
                "Architecture Constraints",
                "Contract                 NOT CONFIGURED",
                "Built-in defaults are being used.",
                "pom.xml",
                "Recommended Strategy",
                "Do not modify code unless explicitly requested.",
                "Pre-Implementation Risk",
                "Test Plan",
                "Acceptance Criteria");
        assertThat(Files.readString(source)).isEqualTo(before);
        assertThat(root.resolve(".agentforge")).doesNotExist();
    }

    @Test
    void bugfixAndAnalysisProduceDifferentStrategies(@TempDir Path root) throws Exception {
        write(root, "src/main/java/demo/AlertSyncService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class AlertSyncService { // 历史 告警 同步 异常
                }
                """);

        CliTestHarness.Result analysis = CliTestHarness.execute(
                "plan", root.toString(), "分析历史告警同步");
        CliTestHarness.Result bugfix = CliTestHarness.execute(
                "plan", root.toString(), "修复历史告警同步异常");

        assertThat(analysis.exitCode()).isZero();
        assertThat(bugfix.exitCode()).isZero();
        assertThat(analysis.output()).contains("Type                     ANALYSIS", "Document the findings.");
        assertThat(bugfix.output()).contains("Type                     BUGFIX", "Identify the root cause.");
        assertThat(analysis.output()).isNotEqualTo(bugfix.output());
    }

    @Test
    void noInspectEvidenceReturnsLowConfidenceWithoutInventingScope(@TempDir Path root) throws Exception {
        write(root, "src/main/java/demo/OrderService.java", "class OrderService {}");

        CliTestHarness.Result result = CliTestHarness.execute(
                "plan", root.toString(), "分析斑马航线");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "PLAN CONFIDENCE LOW",
                "No reliable implementation scope could be derived.",
                "Refine the task description or run inspect with a more specific task.",
                "Do not use LOW or REFERENCE evidence as a modification list.",
                "Files                    0 (Estimated)");
        assertThat(result.output()).doesNotContain("OrderService.java  (");
    }

    @Test
    void dirtyWorkingTreeWarnsButReturnsZeroAndRemainsUnchanged(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project/>");
        Path source = write(root, "src/main/java/demo/LoginService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class LoginService { // 登录 异常
                }
                """);
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        Files.writeString(source, Files.readString(source) + "// modified\n");
        String branchBefore = gitOutput(root, "branch", "--show-current");
        String headBefore = gitOutput(root, "rev-parse", "HEAD");
        String statusBefore = gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all");

        CliTestHarness.Result result = CliTestHarness.execute(
                "plan", root.toString(), "修复登录异常");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "Working Tree             DIRTY",
                "Changed                  1 files",
                "WARNING:\nExisting working tree changes detected.",
                "WARNING: 1 existing changed file overlaps plan scope; PRIMARY overlap detected.",
                "+5 Working tree contains 1 existing changed file",
                "+15 Existing changes overlap PRIMARY plan scope");
        assertThat(gitOutput(root, "branch", "--show-current")).isEqualTo(branchBefore);
        assertThat(gitOutput(root, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all")).isEqualTo(statusBefore);
    }

    @Test
    void invalidPolicyReturnsTwoWithoutFallingBack(@TempDir Path root) throws Exception {
        write(root, "src/main/java/demo/LoginService.java", "class LoginService { // 登录 异常\n }");
        write(root, ".agentforge/policies.yaml", "schema_version: 1\ninvalid: true\n");

        CliTestHarness.Result result = CliTestHarness.execute(
                "plan", root.toString(), "修复登录异常");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Invalid configuration", "invalid");
        assertThat(result.output()).doesNotContain("Built-in defaults are being used.");
    }

    @Test
    void rendersConfiguredArchitectureRulesAsContract(@TempDir Path root) throws Exception {
        write(root, "src/main/java/demo/AlertService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class AlertService { // 历史 告警
                }
                """);
        write(root, ".agentforge/architecture.yaml", """
                schema_version: 1
                architecture:
                  layers: [controller, service, mapper]
                  rules:
                    controller:
                      allow_dependencies: [service]
                    service:
                      allow_dependencies: [mapper]
                    mapper:
                      allow_dependencies: []
                """);

        CliTestHarness.Result result = CliTestHarness.execute(
                "plan", root.toString(), "分析历史告警");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "Contract                 CONFIGURED",
                "Layers                   controller, service, mapper",
                "controller -> service",
                "service -> mapper",
                "mapper -> NONE");
        assertThat(result.output()).doesNotContain("Observed Related Flow (lightweight, not a contract)");
    }

    @Test
    void invalidPathAndMissingTaskReturnTwo() {
        CliTestHarness.Result invalidPath = CliTestHarness.execute(
                "plan", "missing-plan-repository", "修复登录异常");
        CliTestHarness.Result missingTask = CliTestHarness.execute("plan");

        assertThat(invalidPath.exitCode()).isEqualTo(2);
        assertThat(invalidPath.output()).contains("ERROR Path does not exist");
        assertThat(missingTask.exitCode()).isEqualTo(2);
        assertThat(missingTask.output()).contains("Missing required parameter");
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
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
