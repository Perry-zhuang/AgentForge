package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RunTraceArchitectureGuardTest {
    @Test
    void traceLayerPersistsExistingFactsWithoutGovernanceOrConcreteAdapterDependencies() throws Exception {
        String source;
        try (var paths = Files.walk(Path.of("src/main/java/io/agentforge/runtrace"))) {
            source = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).reduce("", String::concat);
        }
        assertThat(source).doesNotContain("InspectService", "PlanEngine", "CheckEngine", "PolicyEngine",
                "ArchitectureScanner", "CodexAdapter", "PlanService", "CheckService", "GitClient",
                "ProcessBuilder", "System.getenv", "OPENAI_API_KEY", "git reset", "git commit");
    }
}
