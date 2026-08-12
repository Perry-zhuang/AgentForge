package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InspectCommandTest {
    @Test
    void inspectsExplicitPathWithDefaultLimit(@TempDir Path root) throws Exception {
        write(root, "src/main/java/demo/LoginService.java", "package demo; @Service class LoginService { // user login\n }");

        CliTestHarness.Result result = CliTestHarness.execute("inspect", root.toString(), "user login");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("AgentForge Task Inspection", "LoginService", "Evidence:",
                "Core Keywords", "Exact Phrases", "Derived Keywords",
                "Related Build Modules", "Related Packages / Source Areas");
    }

    @Test
    void oneArgumentTreatsItAsTaskInCurrentDirectory() {
        CliTestHarness.Result result = CliTestHarness.execute("inspect", "actionable recommendation");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("actionable recommendation", "AgentForge Task Inspection");
    }

    @Test
    void noRelevantFilesStillReturnsZero(@TempDir Path root) throws Exception {
        write(root, "src/OrderService.java", "class OrderService {}");

        CliTestHarness.Result result = CliTestHarness.execute("inspect", root.toString(), "unmatched zebra keyword");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("No high-confidence relevant files found.");
    }

    @Test
    void invalidLimitReturnsTwo(@TempDir Path root) {
        CliTestHarness.Result result = CliTestHarness.execute("inspect", "--limit", "16", root.toString(), "login");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Inspection limit must be between 1 and 15");
    }

    @Test
    void invalidPathReturnsTwo() {
        CliTestHarness.Result result = CliTestHarness.execute("inspect", "missing-inspect-repository", "login");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Path does not exist");
    }

    @Test
    void invalidArchitectureConfigurationReturnsTwo(@TempDir Path root) throws Exception {
        write(root, "src/LoginService.java", "class LoginService {}");
        write(root, ".agentforge/architecture.yaml", "schema_version: 1\nunknown: true\n");

        CliTestHarness.Result result = CliTestHarness.execute("inspect", root.toString(), "login");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("ERROR Invalid configuration", "unknown");
    }

    @Test
    void missingTaskReturnsPicocliUsageError() {
        CliTestHarness.Result result = CliTestHarness.execute("inspect");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Missing required parameter");
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
