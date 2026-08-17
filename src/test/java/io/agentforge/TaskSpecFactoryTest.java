package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.intent.TaskSpecFactory;
import io.agentforge.model.ChangeExpectation;
import io.agentforge.model.TaskSpec;
import io.agentforge.model.TaskType;
import org.junit.jupiter.api.Test;

class TaskSpecFactoryTest {
    private final TaskSpecFactory factory = new TaskSpecFactory();

    @Test
    void generationIsDeterministicAndPreservesOriginalTaskTypeAndCriteria() {
        var plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        TaskSpec first = factory.create(plan);
        TaskSpec second = factory.create(plan);

        assertThat(first).isEqualTo(second);
        assertThat(first.originalTask()).isEqualTo(plan.task());
        assertThat(first.taskType()).isEqualTo(TaskType.BUGFIX);
        assertThat(first.acceptanceCriteria()).containsExactlyElementsOf(
                plan.acceptanceCriteria().stream().map(criterion -> criterion.description()).toList());
        assertThat(first.expectedChanges()).extracting(change -> change.expectation())
                .containsExactly(ChangeExpectation.ANY_ALLOWED_CHANGE,
                        ChangeExpectation.ANY_ALLOWED_CHANGE, ChangeExpectation.NO_CHANGE);
    }

    @Test
    void readOnlyPlanGeneratesNoMutationIntentForEveryKnownScope() {
        TaskSpec spec = factory.create(PromptTestFixtures.plan(TaskType.ANALYSIS));

        assertThat(spec.expectedChanges()).isNotEmpty()
                .allMatch(change -> change.expectation() == ChangeExpectation.NO_CHANGE);
    }
}
