package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.PromptRenderer;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptScopeContractTest {
    @Test
    void rendersPrimarySecondaryAndReferenceModificationContracts() {
        String output = render(TaskType.BUGFIX);

        assertThat(output).contains(
                "PRIMARY — likely change targets",
                "Likely modification scope.",
                "Modify a PRIMARY file only when task evidence supports the change.",
                "SECONDARY — inspect before changing",
                "Do not modify SECONDARY files unless analysis of PRIMARY files demonstrates that the change is necessary.",
                "REFERENCE — read-only context",
                "Treat REFERENCE files as read-only context.",
                "Do not modify them unless direct implementation evidence proves that the task cannot be completed safely without doing so.");
    }

    @Test
    void requiresExplicitReportingForConditionalScopeAndDeviations() {
        String output = render(TaskType.FEATURE);

        assertThat(output).contains(
                "If a SECONDARY file was modified, explain why the modification was necessary.",
                "If a REFERENCE file was modified, explain why read-only context had to be changed.",
                "mark it as SCOPE DEVIATION and explain why.");
    }

    @Test
    void lowConfidencePlanDoesNotPromoteReferenceEvidenceIntoModificationScope() {
        GitBaseline baseline = GitBaseline.unavailable();
        PlanRisk risk = new PlanRisk(15, PlanRisk.Level.LOW, List.of(
                new PlanRisk.Factor(5, "Architecture contract is not configured"),
                new PlanRisk.Factor(10, "Git baseline is not available")));
        PlanScope scope = new PlanScope(List.of(), List.of(),
                List.of(PromptTestFixtures.file("ReferenceOnly", RelevanceLevel.LOW)));
        var plan = PromptTestFixtures.plan(TaskType.UNKNOWN, baseline, PlanScopeOverlap.none(),
                false, true, risk, scope, false);

        String output = new PromptRenderer().render(new PromptEngine().create(plan));

        assertThat(output).contains(
                "Plan Confidence: LOW",
                "Establish a reliable implementation scope before making any code changes.",
                "Do not modify code until a reliable PRIMARY scope is established.",
                "REFERENCE — read-only context",
                "ReferenceOnly.java");
        assertThat(output).doesNotContain("Modify a PRIMARY file only when task evidence supports the change.");
    }

    private String render(TaskType type) {
        return new PromptRenderer().render(new PromptEngine().create(PromptTestFixtures.plan(type)));
    }
}
