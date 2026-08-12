package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.application.InitService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliIntegrationTest {
    @Test
    void helpAndVersionReturnZero() {
        CliTestHarness.Result help = CliTestHarness.execute("--help");
        CliTestHarness.Result version = CliTestHarness.execute("--version");

        assertThat(help.exitCode()).isZero();
        assertThat(help.output()).contains("audit", "init", "diff");
        assertThat(version.exitCode()).isZero();
        assertThat(version.output()).contains("AgentForge 0.1.1-SNAPSHOT");
    }

    @Test
    void auditUsesCurrentDirectoryByDefault() {
        CliTestHarness.Result result = CliTestHarness.execute("audit");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("AgentForge Repository Audit");
    }

    @Test
    void auditsBothFixturesWithExactSuccessExit() {
        CliTestHarness.Result spring = CliTestHarness.execute("audit", "examples/spring-demo");
        CliTestHarness.Result full = CliTestHarness.execute("audit", "examples/spring-vue-demo");

        assertThat(spring.exitCode()).isZero();
        assertThat(spring.output()).contains("SPRING BOOT", "controller -> service",
                "Analysis Mode            LIGHTWEIGHT", "Test File Ratio",
                "+25 Test file ratio >= 10%", "Top Recommendations", "Estimated Overall Impact");
        assertThat(full.exitCode()).isZero();
        assertThat(full.output()).contains("VUE", "MYSQL", "Possible Layer Bypass",
                "+0 Test file ratio below 10% (current 0.00%)");
        assertThat(spring.output()).doesNotContain("No possible layer bypass");
    }

    @Test
    void initReturnsZeroAndIsIdempotent(@TempDir Path root) {
        CliTestHarness.Result first = CliTestHarness.execute("init", root.toString());
        CliTestHarness.Result second = CliTestHarness.execute("init", root.toString());

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).contains("CREATED .agentforge/project.yaml", "CREATED AGENTS.md");
        assertThat(second.exitCode()).isZero();
        assertThat(second.output()).contains("Created\n----------------------------------\nNone", "SKIPPED .agentforge/policies.yaml");
    }

    @Test
    void cleanDiffReturnsZeroWithPerfectMinimalChangeScore(@TempDir Path root) throws Exception {
        baselineRepository(root);

        CliTestHarness.Result result = CliTestHarness.execute("diff", root.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Score                    100 / 100", "Level                    LOW",
                "Architecture Findings In Changed Files");
    }

    @Test
    void protectedViolationReturnsOne(@TempDir Path root) throws Exception {
        baselineRepository(root);
        write(root, "src/main/java/demo/security/Auth.java", "class Auth {}");

        CliTestHarness.Result result = CliTestHarness.execute("diff", root.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("change.protected", "Security Change          Yes");
    }

    @Test
    void denyViolationReturnsOne(@TempDir Path root) throws Exception {
        baselineRepository(root);
        write(root, "pom.xml", "<project><version>changed</version></project>");

        CliTestHarness.Result result = CliTestHarness.execute("diff", root.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("change.deny", "pom.xml");
    }

    @Test
    void invalidPathReturnsTwo() {
        CliTestHarness.Result result = CliTestHarness.execute("audit", "does-not-exist-agentforge-test");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Path does not exist");
    }

    @Test
    void invalidYamlReturnsTwoWithoutFallingBack(@TempDir Path root) throws Exception {
        baselineRepository(root);
        write(root, ".agentforge/policies.yaml", "schema_version: 1\ninvalid: true\n");

        CliTestHarness.Result result = CliTestHarness.execute("diff", root.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Invalid configuration", "invalid");
        assertThat(result.output()).doesNotContain("BUILT-IN DEFAULTS");
    }

    @Test
    void nonGitDiffReturnsTwoWithClearError(@TempDir Path root) {
        CliTestHarness.Result result = CliTestHarness.execute("diff", root.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Not a Git repository");
    }

    @Test
    void unbornRepositorySupportsAuditAndDiff(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "README.md", "demo");
        write(root, "src/main/java/Demo.java", "class Demo {}");

        CliTestHarness.Result audit = CliTestHarness.execute("audit", root.toString());
        CliTestHarness.Result diff = CliTestHarness.execute("diff", root.toString());

        assertThat(audit.exitCode()).isZero();
        assertThat(audit.output()).contains("HEAD                     UNBORN", "Branch                   main");
        assertThat(diff.exitCode()).isZero();
        assertThat(diff.output()).contains("Untracked                2",
                "WARN No .agentforge/policies.yaml; built-in defaults used",
                "Contract                 NOT CONFIGURED");
    }

    @Test
    void detachedRepositorySupportsAuditAndDiff(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        configureIdentity(root);
        new InitService().initialize(root);
        write(root, "README.md", "A");
        git(root, "add", ".");
        git(root, "commit", "-m", "commit A");
        String commitA = gitOutput(root, "rev-parse", "HEAD").strip();
        write(root, "README.md", "B");
        git(root, "commit", "-am", "commit B");
        git(root, "checkout", "--detach", commitA);

        CliTestHarness.Result audit = CliTestHarness.execute("audit", root.toString());
        CliTestHarness.Result diff = CliTestHarness.execute("diff", root.toString());

        assertThat(audit.exitCode()).isZero();
        assertThat(diff.exitCode()).isZero();
        assertThat(audit.output()).contains("Branch                   DETACHED", commitA.substring(0, 12));
    }

    private static void baselineRepository(Path root) throws Exception {
        git(root, "init", "-b", "main");
        configureIdentity(root);
        new InitService().initialize(root);
        write(root, "README.md", "# Demo");
        write(root, "pom.xml", "<project/>");
        write(root, "src/main/java/demo/Demo.java", "package demo; class Demo {}");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
    }

    private static void configureIdentity(Path root) throws Exception {
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
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
