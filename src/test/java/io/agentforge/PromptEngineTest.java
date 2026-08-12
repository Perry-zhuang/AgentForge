package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class PromptEngineTest {
    private final PromptEngine engine = new PromptEngine();

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void mapsEveryExistingPlanTaskTypeWithoutReclassification(TaskType type) {
        EngineeringPrompt prompt = engine.create(PromptTestFixtures.plan(type));

        assertThat(prompt.taskType()).isEqualTo(type);
        assertThat(prompt.objective()).isNotBlank();
        assertThat(prompt.changeInstructions()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void emitsOnlyTaskTypeInstructionsSupportedByThePlan(TaskType type) {
        EngineeringPrompt prompt = engine.create(PromptTestFixtures.plan(type));
        Map<TaskType, String> expected = Map.of(
                TaskType.BUGFIX, "Identify the root cause.",
                TaskType.FEATURE, "Confirm the current extension point.",
                TaskType.INTEGRATION, "Reuse the existing contract.",
                TaskType.REFACTOR, "Preserve external behavior.",
                TaskType.ANALYSIS, "Document the findings.",
                TaskType.UNKNOWN, "Confirm the current task flow.");

        assertThat(prompt.changeInstructions()).contains(expected.get(type));
    }

    @Test
    void analysisDefaultsToNoModificationAndConditionsChangeVerification() {
        EngineeringPrompt prompt = engine.create(PromptTestFixtures.plan(TaskType.ANALYSIS));

        assertThat(prompt.objective()).contains("without modifying source code by default");
        assertThat(prompt.changeInstructions()).contains("Do not modify code unless explicitly requested.")
                .doesNotContain("Execute the generated Test Plan.", "Run agentforge diff after implementation.");
        assertThat(prompt.verificationInstructions()).contains(
                "If code is modified, run Maven tests.",
                "If code is modified, run agentforge diff after implementation.");
    }
}
