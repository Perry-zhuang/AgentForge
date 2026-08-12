package io.agentforge.application;

import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.EngineeringPromptRequest;
import io.agentforge.prompting.PromptEngine;
import org.springframework.stereotype.Service;

@Service
public class PromptService {
    private final PlanService planService;
    private final PromptEngine promptEngine;

    public PromptService(PlanService planService, PromptEngine promptEngine) {
        this.planService = planService;
        this.promptEngine = promptEngine;
    }

    public EngineeringPrompt prompt(EngineeringPromptRequest request) {
        return promptEngine.create(planService.plan(new EngineeringPlanRequest(request.path(), request.task())));
    }
}
