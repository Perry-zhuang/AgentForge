package io.agentforge.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAgentContractTest {
    @Test
    void fakeAdapterReceivesCanonicalRepositoryAndExactRenderedPrompt(@TempDir Path repository) throws Exception {
        CodingAgentIdentity identity = identity();
        FakeCodingAgentAdapter adapter = new FakeCodingAgentAdapter(identity);
        String prompt = "  # frozen prompt\n保留原文\r\ntrailing spaces  \n";
        AgentExecutionRequest request = new AgentExecutionRequest(identity, repository, prompt,
                Duration.ofSeconds(5), Map.of());
        String before = sha256(prompt);

        AgentExecutionResult result = adapter.execute(request);

        assertThat(adapter.received()).isSameAs(request);
        assertThat(adapter.received().repositoryRoot()).isEqualTo(repository.toRealPath());
        assertThat(adapter.received().renderedPrompt()).isEqualTo(prompt);
        assertThat(sha256(adapter.received().renderedPrompt())).isEqualTo(before);
        assertThat(result.status()).isEqualTo(AgentExecutionStatus.SUCCESS);
        assertThat(result.processExitCode()).isZero();
    }

    @Test
    void requestIdentityAndResultAreImmutableRecords(@TempDir Path repository) {
        EnumSet<CodingAgentCapability> capabilities = EnumSet.of(CodingAgentCapability.PROMPT_VIA_STDIN);
        CodingAgentIdentity identity = new CodingAgentIdentity(CodingAgentType.CODEX, "codex", capabilities);
        Map<String, String> overrides = new HashMap<>(Map.of("B", "before"));
        AgentExecutionRequest request = new AgentExecutionRequest(identity, repository, "prompt",
                Duration.ofSeconds(1), overrides);
        AgentExecutionResult result = new AgentExecutionResult(identity, 9, "out", "err", false, false,
                AgentExecutionStatus.FAILED, "");

        capabilities.add(CodingAgentCapability.EXECUTION_TIMEOUT);
        overrides.put("B", "after");

        assertThat(identity.capabilities()).containsExactly(CodingAgentCapability.PROMPT_VIA_STDIN);
        assertThat(request.environmentOverrides()).containsEntry("B", "before");
        assertThatThrownBy(() -> request.environmentOverrides().put("C", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(CodingAgentIdentity.class.isRecord()).isTrue();
        assertThat(AgentExecutionRequest.class.isRecord()).isTrue();
        assertThat(AgentExecutionResult.class.isRecord()).isTrue();
        assertThat(result.processExitCode()).isEqualTo(9);
    }

    @Test
    void registryResolvesOneAdapterAndRejectsMissingOrDuplicateTypes() {
        FakeCodingAgentAdapter adapter = new FakeCodingAgentAdapter(identity());
        CodingAgentRegistry registry = new CodingAgentRegistry(List.of(adapter));

        assertThat(registry.resolve(CodingAgentType.CODEX)).isSameAs(adapter);
        assertThatThrownBy(() -> new CodingAgentRegistry(List.of(adapter,
                new FakeCodingAgentAdapter(identity()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate coding agent adapter for CODEX");
        assertThatThrownBy(() -> new CodingAgentRegistry(List.of()).resolve(CodingAgentType.CODEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No coding agent adapter registered for CODEX");
    }

    @Test
    void agentTypeParsingHasExplicitUnknownSemantics() {
        assertThat(CodingAgentType.parse("codex")).isEqualTo(CodingAgentType.CODEX);
        assertThatThrownBy(() -> CodingAgentType.parse("claude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown coding agent type: claude");
    }

    static CodingAgentIdentity identity() {
        return new CodingAgentIdentity(CodingAgentType.CODEX, "codex-test", EnumSet.allOf(
                CodingAgentCapability.class));
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
