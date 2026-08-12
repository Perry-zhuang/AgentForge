package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.PromptRenderer;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptPolicyTest {
    @Test
    void identifiesBuiltInPolicyWithoutCallingItRepositoryPolicy() {
        String output = render(false, true);

        assertThat(output).contains(
                "Policy source: AgentForge built-in defaults.",
                "because the repository does not provide .agentforge/policies.yaml.",
                "pom.xml", "src/main/java/**/security/**");
        assertThat(output).doesNotContain("Policy source: .agentforge/policies.yaml.");
    }

    @Test
    void rendersRepositoryPolicyAndConfiguredArchitectureContract() {
        String output = render(true, false);

        assertThat(output).contains(
                "Policy source: .agentforge/policies.yaml.",
                "Configured Architecture Contract",
                "Layer: controller",
                "Allowed: controller -> service",
                "Forbidden: controller -> controller",
                "Forbidden: service -> controller, service");
        assertThat(output).doesNotContain("Observed dependency flows are lightweight evidence only.");
    }

    @Test
    void keepsObservedFlowLightweightWhenArchitectureContractIsMissing() {
        String output = render(false, false);

        assertThat(output).contains(
                "Architecture contract is not configured.",
                "Observed dependency flows are lightweight evidence only.",
                "Do not treat them as authoritative architecture rules.",
                "Do not invent architectural constraints.",
                "Observed flow: demo.PrimaryService -> demo.SecondaryService");
    }

    private String render(boolean architectureConfigured, boolean defaultPolicy) {
        var baseline = new GitBaseline(true, "main", "0123456789abcdef",
                GitBaseline.WorkingTreeState.CLEAN, 0);
        var risk = new PlanRisk(5, PlanRisk.Level.LOW,
                List.of(new PlanRisk.Factor(5, "Architecture contract is not configured")));
        var plan = PromptTestFixtures.plan(TaskType.FEATURE, baseline, PlanScopeOverlap.none(),
                architectureConfigured, defaultPolicy, risk);
        return new PromptRenderer().render(new PromptEngine().create(plan));
    }
}
