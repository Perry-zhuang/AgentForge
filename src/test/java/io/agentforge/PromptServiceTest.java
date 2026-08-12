package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentforge.application.PlanService;
import io.agentforge.application.PromptService;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPromptRequest;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PromptServiceTest {
    @Test
    void callsPlanServiceExactlyOnceAndTransformsOnlyThatPlan() {
        PlanService planService = mock(PlanService.class);
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        when(planService.plan(any())).thenReturn(plan);
        PromptService service = new PromptService(planService, new PromptEngine());

        var prompt = service.prompt(new EngineeringPromptRequest(Path.of("."), "修复任务异常"));

        assertThat(prompt.task()).isEqualTo(plan.task());
        verify(planService).plan(any());
        verifyNoMoreInteractions(planService);
    }
}
