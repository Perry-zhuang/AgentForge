package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.AgentRunResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentExecutionArchitectureGuardTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/io/agentforge/application/AgentExecutionService.java");

    @Test
    void orchestratorDependsOnControlPlanePortsAndRegistryNotConcreteAgentsOrEngines() throws IOException {
        String source = Files.readString(SERVICE);

        assertThat(source).contains("CodingAgentRegistry", "EngineeringPromptRenderer")
                .doesNotContain("CodexAdapter", "DeterministicProcessExecutor", "ProcessBuilder",
                        "PromptEngine", "CheckEngine", "PlanEngine", "PolicyEngine", "ArchitectureScanner",
                        "RepositoryScanner", "GitClient", "Files.write", "Files.delete", "Files.move",
                        "git add", "git commit", "git reset", "git restore", "git clean", "git stash");
    }

    @Test
    void lifecycleOrderIsLockedInProductionSource() throws IOException {
        String source = Files.readString(SERVICE);

        assertThat(List.of(
                source.indexOf("planService.plan"),
                source.indexOf("agentRegistry.resolve"),
                source.indexOf("discoveryCoordinator.discover"),
                source.indexOf("promptService.fromPlan"),
                source.indexOf("promptRenderer.render"),
                source.indexOf("executionBaselineService.capture"),
                source.indexOf("runTraceService.persistDiscovery"),
                source.indexOf("adapter.execute"),
                source.indexOf("executionBaselineService.compare"),
                source.indexOf("checkService.checkExecution")))
                .doesNotContain(-1)
                .isSorted();
    }

    @Test
    void aggregateResultDoesNotExposeRenderedPromptOrGovernanceRecomputationInputs() {
        assertThat(List.of(AgentRunResult.class.getRecordComponents()).stream()
                .map(component -> component.getName()))
                .containsExactly("repositoryRoot", "task", "taskType", "agent", "plan", "prompt",
                        "agentExecutionResult", "executionChangeReport", "verificationReport", "status", "runTrace")
                .doesNotContain("renderedPrompt", "repositorySnapshot", "policyReport", "architectureReport");
    }

    @Test
    void orchestratorRemainsIndependentFromTheLaterRunCli() throws IOException {
        String service = Files.readString(SERVICE);
        assertThat(service).doesNotContain("RunCommand", "RunRenderer", "io.agentforge.cli");
    }
}
