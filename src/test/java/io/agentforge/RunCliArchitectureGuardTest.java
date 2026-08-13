package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RunCliArchitectureGuardTest {
    @Test
    void runCommandIsAThinOrchestratorClientWithoutGovernanceOrConcreteAdapterDependencies() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/agentforge/cli/RunCommand.java"));

        assertThat(source).contains("AgentExecutionService", "AgentRunRequest", "AgentRunResult",
                        "CodingAgentType.parse", "executionService.execute(request)", "renderer.render(result)")
                .doesNotContain("PlanService", "InspectService", "PromptService", "ExecutionBaselineService",
                        "BaselineService", "CheckService", "CheckEngine", "PolicyEngine", "ArchitectureScanner",
                        "CodexAdapter", "DeterministicProcessExecutor", "ProcessBuilder", "GitClient",
                        "Files.write", "git add", "git commit", "git reset", "git restore", "git clean",
                        "git stash");
        assertThat(occurrences(source, "executionService.execute(request)")).isOne();
    }

    @Test
    void runRendererOnlyFormatsAggregateModelsAndReadsNoExternalState() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/agentforge/cli/RunRenderer.java"));

        assertThat(source).contains("AgentRunResult")
                .doesNotContain("io.agentforge.application", "io.agentforge.agent.CodexAdapter",
                        "java.nio.file", "ConfigLoader", "GitClient", "ProcessBuilder", "Files.",
                        "PlanService", "PromptService", "CheckService");
    }

    @Test
    void rootRegistersRunAlongsideEveryExistingCommand() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/agentforge/cli/AgentForgeCommand.java"));

        assertThat(source).contains("AuditCommand.class", "InitCommand.class", "DiffCommand.class",
                "InspectCommand.class", "PlanCommand.class", "PromptCommand.class", "RunCommand.class",
                "CheckCommand.class", "BaselineCommand.class");
    }

    private int occurrences(String source, String token) {
        return (source.length() - source.replace(token, "").length()) / token.length();
    }
}
