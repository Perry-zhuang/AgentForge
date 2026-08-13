package io.agentforge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAdapterArchitectureGuardTest {
    private static final List<String> FORBIDDEN_INTERNAL_IMPORT_PREFIXES = List.of(
            "import io.agentforge.application.",
            "import io.agentforge.baseline.",
            "import io.agentforge.git.",
            "import io.agentforge.inspection.",
            "import io.agentforge.model.",
            "import io.agentforge.planning.",
            "import io.agentforge.policy.",
            "import io.agentforge.prompting.",
            "import io.agentforge.recommendation.",
            "import io.agentforge.risk.",
            "import io.agentforge.scanner.",
            "import io.agentforge.verification."
    );

    @Test
    void adapterPackageCannotDependOnControlPlaneOrGovernanceDomains() throws IOException {
        for (Path source : adapterSources()) {
            List<String> imports = Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .toList();
            for (String forbidden : FORBIDDEN_INTERNAL_IMPORT_PREFIXES) {
                assertThat(imports).as(source.toString()).noneMatch(line -> line.startsWith(forbidden));
            }
        }
    }

    @Test
    void adapterPackageContainsNoRepositoryMutationOrPromptRecomputeCode() throws IOException {
        String sources = adapterSources().stream().map(this::read).reduce("", (left, right) -> left + right);

        assertThat(sources).doesNotContain("Files.write", "Files.createFile", "StandardOpenOption",
                "git add", "git commit", "git stash", "git reset", "git checkout", "git clean",
                "PromptService", "PromptEngine", "PlanService", "InspectService", "CheckEngine");
    }

    @Test
    void requestAndResultExposeOnlyProcessDrivingFacts() {
        assertThat(List.of(AgentExecutionRequest.class.getRecordComponents()).stream()
                .map(component -> component.getName() + ":" + component.getType().getName()))
                .containsExactly(
                        "agent:io.agentforge.agent.CodingAgentIdentity",
                        "repositoryRoot:java.nio.file.Path",
                        "renderedPrompt:java.lang.String",
                        "timeout:java.time.Duration",
                        "environmentOverrides:java.util.Map");
        assertThat(List.of(AgentExecutionResult.class.getRecordComponents()).stream()
                .map(component -> component.getType().getPackageName()))
                .allMatch(packageName -> packageName.equals("io.agentforge.agent")
                        || packageName.equals("java.lang"));
    }

    private List<Path> adapterSources() throws IOException {
        Path root = Path.of("src/main/java/io/agentforge/agent");
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
