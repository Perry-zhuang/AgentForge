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
    void repositoryOnlyBaselineAndTaskMismatchFailExplicitlyWithoutCurrentPlanFallback(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path repositoryOnly = temp.resolve("repository-only.json");
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", repositoryOnly.toString()).exitCode()).isZero();
        Path taskAware = capture(root, "修复登录异常", temp.resolve("task-aware.json"));

        CliTestHarness.Result missingBoundary = executionCheck(root, "修复登录异常", repositoryOnly);
        CliTestHarness.Result mismatch = executionCheck(root, "分析登录流程", taskAware);

        assertThat(missingBoundary.exitCode()).isEqualTo(2);
        assertThat(missingBoundary.output()).contains(
                "does not contain an execution engineering boundary",
                "recapture baseline with --task TASK").doesNotContain("CHECK EXECUTION FAIL");
        assertThat(mismatch.exitCode()).isEqualTo(2);
        assertThat(mismatch.output()).contains("Baseline task does not match requested task")
                .doesNotContain("CHECK EXECUTION FAIL");
    }

    @Test
    void afterExecutionPlanDriftCannotLegalizeNewTaskRelevantSource(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        String task = "修复登录异常";
        Path baseline = capture(root, task, temp.resolve("before.json"));
        String evilPath = "src/main/java/demo/OutsideEvilService.java";
        write(root, evilPath, """
                package demo;
                import org.springframework.stereotype.Service;
                @Service class OutsideEvilService {
                    // 登录 登录 登录 异常 异常 token auth login exception
                    void repairLoginAuthTokenException() { }
                }
                """);

        CliTestHarness.Result afterPlan = CliTestHarness.execute("plan", root.toString(), task);
        CliTestHarness.Result result = executionCheck(root, task, baseline);
        String primary = afterPlan.output().substring(afterPlan.output().indexOf("PRIMARY —"),
                afterPlan.output().indexOf("SECONDARY —"));

        assertThat(afterPlan.exitCode()).isZero();
        assertThat(primary).contains(evilPath);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains(
                "ADDED " + evilPath,
                "OUT_OF_SCOPE_MODIFICATION " + evilPath,
                "CHECK EXECUTION FAIL");
    }

    @Test
    void compatiblePrimaryRenameUsesBaselineEntityScope(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        String task = "修复登录异常";
        Path baseline = capture(root, task, temp.resolve("before.json"));
        git(root, "mv", "src/main/java/demo/LoginService.java",
                "src/main/java/demo/LoginRenamedService.java");

        CliTestHarness.Result result = executionCheck(root, task, baseline);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "RENAMED src/main/java/demo/LoginService.java -> src/main/java/demo/LoginRenamedService.java",
                "CHECK EXECUTION PASS").doesNotContain("OUT_OF_SCOPE_MODIFICATION");
    }

    @Test
    void baselineAnalysisTaskAndPolicyCannotBeRelaxedByExecution(@TempDir Path temp) throws Exception {
        Path analysisRoot = repository(temp.resolve("analysis"));
        Path analysisBaseline = capture(analysisRoot, "分析登录流程", temp.resolve("analysis.json"));
        write(analysisRoot, "src/main/java/demo/LoginGeneratedService.java", """
                package demo;
                class LoginGeneratedService { // 登录 流程 分析
                }
                """);

        CliTestHarness.Result analysis = executionCheck(
                analysisRoot, "分析登录流程", analysisBaseline);

        assertThat(analysis.exitCode()).isEqualTo(1);
        assertThat(analysis.output()).contains(
                "Task Type                ANALYSIS",
                "ANALYSIS_SOURCE_MODIFICATION",
                "CHECK EXECUTION FAIL");

        Path policyRoot = repository(temp.resolve("policy"));
        write(policyRoot, ".agentforge/policies.yaml", policyYaml(20, "[]"));
        Path policyBaseline = capture(policyRoot, "修复登录异常", temp.resolve("policy.json"));
        write(policyRoot, ".agentforge/policies.yaml", policyYaml(100, "[]"));
        for (int index = 0; index < 20; index++) {
            write(policyRoot, "generated/File" + index + ".txt", "execution\n");
        }

        CliTestHarness.Result relaxed = executionCheck(policyRoot, "修复登录异常", policyBaseline);

        assertThat(relaxed.exitCode()).isEqualTo(1);
        assertThat(relaxed.output()).contains(
                "Execution Files          21",
                "Changed 21 files; maximum is 20",
                "CHECK EXECUTION FAIL").doesNotContain("maximum is 100");
    }

    @Test
    void baselineDeniedRuleAndArchitectureContractCannotBeRelaxedByExecution(@TempDir Path temp)
            throws Exception {
        Path deniedRoot = repository(temp.resolve("denied"));
        write(deniedRoot, ".agentforge/policies.yaml", policyYaml(20, "[\"secret/**\"]"));
        Path deniedBaseline = capture(deniedRoot, "修复登录异常", temp.resolve("denied.json"));
        write(deniedRoot, ".agentforge/policies.yaml", policyYaml(20, "[]"));
        write(deniedRoot, "secret/blocked.txt", "execution\n");

        CliTestHarness.Result denied = executionCheck(deniedRoot, "修复登录异常", deniedBaseline);

        assertThat(denied.exitCode()).isEqualTo(1);
        assertThat(denied.output()).contains(
                "change.deny secret/blocked.txt",
                "pattern: secret/**",
                "CHECK EXECUTION FAIL");

        Path protectedRoot = repository(temp.resolve("protected"));
        write(protectedRoot, ".agentforge/policies.yaml",
                policyYaml(20, "[]", "[\"protected/**\"]"));
        Path protectedBaseline = capture(
                protectedRoot, "修复登录异常", temp.resolve("protected.json"));
        write(protectedRoot, ".agentforge/policies.yaml", policyYaml(20, "[]", "[]"));
        write(protectedRoot, "protected/blocked.txt", "execution\n");

        CliTestHarness.Result protectedResult = executionCheck(
                protectedRoot, "修复登录异常", protectedBaseline);

        assertThat(protectedResult.exitCode()).isEqualTo(1);
        assertThat(protectedResult.output()).contains(
                "change.protected protected/blocked.txt",
                "pattern: protected/**",
                "CHECK EXECUTION FAIL");

        Path architectureRoot = architectureRepository(temp.resolve("architecture"), false);
        Path architectureBaseline = capture(
                architectureRoot, "fix LoginController", temp.resolve("architecture.json"));
        write(architectureRoot, ".agentforge/architecture.yaml", """
                schema_version: 1
                architecture:
                  layers: [controller, service, repository, mapper]
                  rules:
                    controller:
                      allow_dependencies: [service, mapper]
                    service:
                      allow_dependencies: [repository, mapper]
                    repository:
                      allow_dependencies: []
                    mapper:
                      allow_dependencies: []
                """);
        write(architectureRoot, "src/main/java/demo/LoginController.java", controllerSource());

        CliTestHarness.Result architecture = executionCheck(
                architectureRoot, "fix LoginController", architectureBaseline);

        assertThat(architecture.exitCode()).isEqualTo(1);
        assertThat(architecture.output()).contains(
                "ARCHITECTURE_CONSTRAINT_VIOLATION",
                "controller must not depend on mapper",
                "CHECK EXECUTION FAIL");
    }

    @Test
    void dirtyAnalysisRepositoryHasDifferentCurrentAndExecutionResultsWithoutMutation(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        append(root.resolve("src/main/java/demo/LoginService.java"), "// historical login change\n");
        append(root.resolve("src/main/java/demo/OutsideService.java"), "// historical outside change\n");
        Path baseline = capture(root, "分析登录流程", temp.resolve("before.json"));
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
        Path baseline = capture(root, "修复登录异常", temp.resolve("before.json"));

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
        Path isolatedBaseline = capture(isolatedRoot, "修复登录异常", temp.resolve("isolated.json"));
        append(isolatedRoot.resolve("src/main/java/demo/LoginService.java"), "// execution change\n");

        CliTestHarness.Result isolated = executionCheck(isolatedRoot, "修复登录异常", isolatedBaseline);

        assertThat(isolated.exitCode()).isZero();
        assertThat(isolated.output()).contains("Execution Files          1", "CHECK EXECUTION PASS")
                .doesNotContain("change.max_files", "Changed 43 files");

        Path exceededRoot = repository(temp.resolve("exceeded"));
        Path exceededBaseline = capture(exceededRoot, "修复登录异常", temp.resolve("exceeded.json"));
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
        Path baseline = capture(root, "分析登录流程", temp.resolve("before.json"));
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
        Path baseline = capture(first, "修复登录异常", temp.resolve("first.json"));
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
        Path baseline = capture(root, "fix LoginController", temp.resolve("before.json"));
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
        Path baseline = capture(root, "fix LoginController", temp.resolve("before.json"));
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

    private static String policyYaml(int maxFiles, String deny) {
        return policyYaml(maxFiles, deny, "[]");
    }

    private static String policyYaml(int maxFiles, String deny, String protectedPaths) {
        return """
                schema_version: 1
                change:
                  max_files: %d
                  deny: %s
                  protected: %s
                minimal_change:
                  free_files: 5
                  per_extra_file: 2
                  max_file_penalty: 30
                  free_modules: 1
                  per_extra_module: 8
                  max_module_penalty: 24
                  config_change_penalty: 10
                  dependency_change_penalty: 15
                  database_change_penalty: 15
                  security_change_penalty: 20
                  agents_change_penalty: 10
                risk:
                  medium_threshold: 20
                  high_threshold: 40
                  critical_threshold: 70
                  max_modules: 3
                  policy_violation: 30
                  dependency_change: 15
                  security_change: 20
                  database_change: 15
                  files_over_limit: 20
                  modules_over_limit: 10
                  controller_change: 10
                  agents_change: 10
                  config_change: 5
                readiness:
                  context:
                    agents: 35
                    readme: 15
                    skills: 10
                    agent_rules: 10
                    git_rules: 10
                    database_rules: 5
                    api_rules: 5
                    testing_rules: 10
                    agents_too_large_penalty: 10
                    agents_max_lines: 300
                  architecture:
                    architecture_doc: 40
                    architecture_contract: 35
                    recognizable_structure: 10
                    no_layer_bypass: 15
                  change_safety:
                    policies: 40
                    gitignore: 15
                    git_repository: 15
                    git_rules: 10
                    database_rules: 10
                    api_rules: 10
                  testability:
                    tests_present: 60
                    test_ratio: 25
                    testing_rules: 15
                    minimum_test_ratio: 0.10
                  documentation:
                    readme: 35
                    architecture_doc: 35
                    agents: 20
                    docs_directory: 10
                """.formatted(maxFiles, deny, protectedPaths);
    }

    private static Path capture(Path root, String task, Path baseline) {
        CliTestHarness.Result capture = CliTestHarness.execute(
                "baseline", "capture", root.toString(), "--task", task,
                "--output", baseline.toString());
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
