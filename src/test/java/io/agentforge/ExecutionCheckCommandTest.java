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

class ExecutionCheckCommandTest {
    @Test
    void dirtyAnalysisRepositoryHasDifferentCurrentAndExecutionResultsWithoutMutation(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        append(root.resolve("src/main/java/demo/LoginService.java"), "// historical login change\n");
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// historical outside change\n");
        Path baseline = capture(root, temp.resolve("before.json"));
        String branchBefore = gitOutput(root, "branch", "--show-current");
        String headBefore = gitOutput(root, "rev-parse", "HEAD");
        String statusBefore = gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all");
        String indexBefore = gitOutput(root, "ls-files", "--stage");
        Map<String, String> filesBefore = fileHashes(root);
        String baselineHashBefore = hash(Files.readAllBytes(baseline));

        CliTestHarness.Result current = CliTestHarness.execute(
                "check", root.toString(), "分析登录流程");
        CliTestHarness.Result first = executionCheck(root, "分析登录流程", baseline);
        CliTestHarness.Result second = executionCheck(root, "分析登录流程", baseline);
        CliTestHarness.Result third = executionCheck(root, "分析登录流程", baseline);

        assertThat(current.exitCode()).isEqualTo(1);
        assertThat(current.output()).contains("Baseline                 CURRENT_WORKING_TREE", "CHECK FAIL");
        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).contains(
                "Baseline                 EXECUTION_DELTA",
                "Execution Files          0",
                "Scope Compliance",
                "Task-Type Compliance",
                "Status                   NOT CONFIGURED",
                "CHECK EXECUTION PASS");
        assertThat(first.output()).doesNotContain("ANALYSIS_SOURCE_MODIFICATION", "OUT_OF_SCOPE_MODIFICATION");
        assertThat(first.output()).isEqualTo(second.output()).isEqualTo(third.output());
        assertThat(gitOutput(root, "branch", "--show-current")).isEqualTo(branchBefore);
        assertThat(gitOutput(root, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(gitOutput(root, "status", "--porcelain=v1", "--untracked-files=all"))
                .isEqualTo(statusBefore);
        assertThat(gitOutput(root, "ls-files", "--stage")).isEqualTo(indexBefore);
        assertThat(fileHashes(root)).isEqualTo(filesBefore);
        assertThat(hash(Files.readAllBytes(baseline))).isEqualTo(baselineHashBefore);
    }

    @Test
    void historicalOutOfScopePathOnlyFailsAfterItIsModifiedAgain(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// dirty before\n");
        Path baseline = capture(root, temp.resolve("before.json"));

        CliTestHarness.Result unchanged = executionCheck(root, "修复登录异常", baseline);
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// modified again\n");
        CliTestHarness.Result modified = executionCheck(root, "修复登录异常", baseline);

        assertThat(unchanged.exitCode()).isZero();
        assertThat(unchanged.output()).contains("Execution Files          0", "CHECK EXECUTION PASS")
                .doesNotContain("OUT_OF_SCOPE_MODIFICATION");
        assertThat(modified.exitCode()).isEqualTo(1);
        assertThat(modified.output()).contains(
                "Execution Files          1",
                "MODIFIED src/main/java/demo/OutsideService.java",
                "OUT_OF_SCOPE_MODIFICATION",
                "CHECK EXECUTION FAIL");
    }

    @Test
    void executionPolicyCountsOnlyExecutionEntriesAndEnforcesItsOwnLimit(@TempDir Path temp) throws Exception {
        Path isolatedRoot = repository(temp.resolve("isolated"));
        for (int index = 0; index < 42; index++) {
            write(isolatedRoot, "historical/File" + index + ".txt", "dirty before\n");
        }
        Path isolatedBaseline = capture(isolatedRoot, temp.resolve("isolated.json"));
        append(isolatedRoot.resolve("src/main/java/demo/LoginService.java"), "// execution change\n");

        CliTestHarness.Result isolated = executionCheck(isolatedRoot, "修复登录异常", isolatedBaseline);

        assertThat(isolated.exitCode()).isZero();
        assertThat(isolated.output()).contains("Execution Files          1", "CHECK EXECUTION PASS")
                .doesNotContain("change.max_files", "Changed 43 files");

        Path exceededRoot = repository(temp.resolve("exceeded"));
        Path exceededBaseline = capture(exceededRoot, temp.resolve("exceeded.json"));
        for (int index = 0; index < 21; index++) {
            write(exceededRoot, "generated/File" + index + ".txt", "execution\n");
        }

        CliTestHarness.Result exceeded = executionCheck(exceededRoot, "修复登录异常", exceededBaseline);

        assertThat(exceeded.exitCode()).isEqualTo(1);
        assertThat(exceeded.output()).contains(
                "Execution Files          21",
                "change.max_files",
                "Changed 21 files; maximum is 20",
                "CHECK EXECUTION FAIL");
    }

    @Test
    void stagingOnlyPrimarySourceIsScopeActivityButNotAnalysisContentChange(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path source = root.resolve("src/main/java/demo/LoginService.java");
        append(source, "// dirty before baseline\n");
        Path baseline = capture(root, temp.resolve("before.json"));
        git(root, "add", "src/main/java/demo/LoginService.java");

        CliTestHarness.Result result = executionCheck(root, "分析登录流程", baseline);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "Execution Files          1",
                "GIT_STATE_CHANGED src/main/java/demo/LoginService.java GIT_STATE_CHANGED",
                "CHECK EXECUTION PASS");
        assertThat(result.output()).doesNotContain("ANALYSIS_SOURCE_MODIFICATION");
    }

    @Test
    void executionCheckRejectsInvalidInternalAndMismatchedBaselinesWithExitTwo(@TempDir Path temp)
            throws Exception {
        Path first = repository(temp.resolve("first"));
        Path second = repository(temp.resolve("second"));
        Path baseline = capture(first, temp.resolve("first.json"));
        Path invalid = temp.resolve("invalid.json");
        Files.writeString(invalid, "{invalid}");
        Path internal = first.resolve("inside.json");
        Files.copy(baseline, internal);

        CliTestHarness.Result missing = executionCheck(first, "修复登录异常", temp.resolve("missing.json"));
        CliTestHarness.Result malformed = executionCheck(first, "修复登录异常", invalid);
        CliTestHarness.Result mismatch = executionCheck(second, "修复登录异常", baseline);
        CliTestHarness.Result inside = executionCheck(first, "修复登录异常", internal);

        assertThat(missing.exitCode()).isEqualTo(2);
        assertThat(missing.output()).contains("Baseline file does not exist");
        assertThat(malformed.exitCode()).isEqualTo(2);
        assertThat(malformed.output()).contains("Invalid baseline file");
        assertThat(mismatch.exitCode()).isEqualTo(2);
        assertThat(mismatch.output()).contains("does not match current repository");
        assertThat(inside.exitCode()).isEqualTo(2);
        assertThat(inside.output()).contains("must be outside the target repository");
    }

    @Test
    void modifiedExistingArchitectureViolationIsLimitedButNotAttributed(@TempDir Path temp) throws Exception {
        Path root = architectureRepository(temp.resolve("repo"), true);
        Path baseline = capture(root, temp.resolve("before.json"));
        append(root.resolve("src/main/java/demo/LoginController.java"), "// changed existing source\n");

        CliTestHarness.Result result = executionCheck(root, "fix LoginController", baseline);

        assertThat(result.output()).contains(
                "Architecture Compliance",
                "Architecture execution attribution is limited",
                "CHECK EXECUTION PASS");
        assertThat(result.output()).doesNotContain("ARCHITECTURE_CONSTRAINT_VIOLATION");
    }

    @Test
    void violationFromAddedArchitectureSourceIsExecutionAttributed(@TempDir Path temp) throws Exception {
        Path root = architectureRepository(temp.resolve("repo"), false);
        Path baseline = capture(root, temp.resolve("before.json"));
        write(root, "src/main/java/demo/LoginController.java", controllerSource());

        CliTestHarness.Result result = executionCheck(root, "fix LoginController", baseline);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(
                "Execution Files          1",
                "ARCHITECTURE_CONSTRAINT_VIOLATION",
                "CHECK EXECUTION FAIL");
    }

    private static Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        write(root, "README.md", "# Demo\n");
        write(root, "src/main/java/demo/LoginService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class LoginService { // 登录 异常 流程
                }
                """);
        write(root, "src/main/java/demo/OutsideService.java", """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class OutsideService {
                }
                """);
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        return root;
    }

    private static Path architectureRepository(Path root, boolean includeController) throws Exception {
        repository(root);
        write(root, ".agentforge/architecture.yaml", """
                schema_version: 1
                architecture:
                  layers: [controller, service, repository, mapper]
                  rules:
                    controller:
                      allow_dependencies: [service]
                    service:
                      allow_dependencies: [repository, mapper]
                    repository:
                      allow_dependencies: []
                    mapper:
                      allow_dependencies: []
                """);
        write(root, "src/main/java/demo/LoginMapper.java", """
                package demo;
                @Mapper class LoginMapper { }
                """);
        if (includeController) {
            write(root, "src/main/java/demo/LoginController.java", controllerSource());
        }
        git(root, "add", ".");
        git(root, "commit", "-m", "architecture fixture");
        return root;
    }

    private static String controllerSource() {
        return """
                package demo;
                @Controller class LoginController {
                    LoginController(LoginMapper mapper) { }
                }
                """;
    }

    private static Path capture(Path root, Path baseline) {
        CliTestHarness.Result capture = CliTestHarness.execute(
                "baseline", "capture", root.toString(), "--output", baseline.toString());
        assertThat(capture.exitCode()).isZero();
        return baseline;
    }

    private static CliTestHarness.Result executionCheck(Path root, String task, Path baseline) {
        return CliTestHarness.execute(
                "check", root.toString(), task, "--baseline", baseline.toString());
    }

    private static Map<String, String> fileHashes(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git")))
                    .sorted().toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'), hash(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
