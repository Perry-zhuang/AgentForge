package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TaskClassificationArchitectureGuardTest {
    @Test
    void classifierHasNoAdapterCliTraceOrVerificationDependencies() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/agentforge/planning/TaskTypeClassifier.java"));
        assertThat(source).doesNotContain("CodexAdapter", "RunRenderer", "runtrace", "CheckEngine",
                "java.nio.file", "java.net", "ProcessBuilder");
    }

    @Test
    void preflightConsumesOnlyClassificationFacts() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/agentforge/planning/RunPreflightService.java"));
        assertThat(source).doesNotContain("InspectService", "PlanService", "CodexAdapter",
                "RepositoryScanner", "GitChangeScanner", "java.nio.file");
        assertThat(source).contains("TaskClassification");
    }

    @Test
    void runCommandDoesNotOwnClassifierDictionariesOrConcreteGovernanceServices() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/agentforge/cli/RunCommand.java"));
        assertThat(source).doesNotContain("TaskTypeClassifier", "RunPreflightService", "PlanService",
                "InspectService", "CodexAdapter");
    }
}
