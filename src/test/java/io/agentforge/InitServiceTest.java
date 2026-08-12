package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.application.InitService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitServiceTest {
    private final InitService service = new InitService();

    @Test
    void createsAllMissingFilesForEmptyProject(@TempDir Path root) {
        InitService.InitResult result = service.initialize(root);

        assertThat(result.created()).containsExactlyInAnyOrder(
                ".agentforge/project.yaml", ".agentforge/architecture.yaml", ".agentforge/policies.yaml",
                ".agentforge/README.md", "AGENTS.md");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void completesPartialDirectoryAndPreservesExistingAgentsAndProject(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".agentforge"));
        Files.writeString(root.resolve("AGENTS.md"), "existing agents");
        Files.writeString(root.resolve(".agentforge/project.yaml"), "custom project");

        InitService.InitResult result = service.initialize(root);

        assertThat(result.created()).containsExactlyInAnyOrder(
                ".agentforge/architecture.yaml", ".agentforge/policies.yaml", ".agentforge/README.md");
        assertThat(result.skipped()).containsExactlyInAnyOrder("AGENTS.md", ".agentforge/project.yaml");
        assertThat(Files.readString(root.resolve("AGENTS.md"))).isEqualTo("existing agents");
        assertThat(Files.readString(root.resolve(".agentforge/project.yaml"))).isEqualTo("custom project");
    }

    @Test
    void repeatedInitPreservesCustomPoliciesByteForByte(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".agentforge"));
        byte[] custom = new byte[] {0x23, 0x20, 0x63, 0x75, 0x73, 0x74, 0x6f, 0x6d, 0x0a};
        Files.write(root.resolve(".agentforge/policies.yaml"), custom);

        InitService.InitResult first = service.initialize(root);
        byte[] afterFirst = Files.readAllBytes(root.resolve(".agentforge/policies.yaml"));
        InitService.InitResult second = service.initialize(root);

        assertThat(first.skipped()).contains(".agentforge/policies.yaml");
        assertThat(afterFirst).containsExactly(custom);
        assertThat(Files.readAllBytes(root.resolve(".agentforge/policies.yaml"))).containsExactly(custom);
        assertThat(second.created()).isEmpty();
        assertThat(second.skipped()).hasSize(5);
    }
}
