package io.agentforge.application;

import io.agentforge.agent.AgentExecutionRequest;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentRegistry;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.VerificationReport;
import io.agentforge.prompting.EngineeringPromptRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Deterministic sequential orchestration over existing planning, execution, delta, and check boundaries. */
@Service
public class AgentExecutionService {
    private final PlanService planService;
    private final PromptService promptService;
    private final EngineeringPromptRenderer promptRenderer;
    private final ExecutionBaselineService executionBaselineService;
    private final CodingAgentRegistry agentRegistry;
    private final CheckService checkService;

    public AgentExecutionService(PlanService planService, PromptService promptService,
                                 EngineeringPromptRenderer promptRenderer,
                                 ExecutionBaselineService executionBaselineService,
                                 CodingAgentRegistry agentRegistry, CheckService checkService) {
        this.planService = planService;
        this.promptService = promptService;
        this.promptRenderer = promptRenderer;
        this.executionBaselineService = executionBaselineService;
        this.agentRegistry = agentRegistry;
        this.checkService = checkService;
    }

    public AgentRunResult execute(AgentRunRequest request) {
        if (request == null) {
            throw failure(AgentRunException.Phase.REPOSITORY_VALIDATION,
                    new IllegalArgumentException("Agent run request is required"), null, null);
        }
        Path requestedRoot = phase(AgentRunException.Phase.REPOSITORY_VALIDATION,
                () -> canonicalDirectory(request.repositoryRoot()));
        EngineeringPlan plan = phase(AgentRunException.Phase.PLAN,
                () -> planService.plan(new EngineeringPlanRequest(requestedRoot, request.task())));
        Path repositoryRoot = plan.repository().repository().path();
        EngineeringPrompt prompt = phase(AgentRunException.Phase.PROMPT,
                () -> promptService.fromPlan(plan));
        String renderedPrompt = phase(AgentRunException.Phase.PROMPT,
                () -> promptRenderer.render(prompt));
        ExecutionBaseline baseline = phase(AgentRunException.Phase.BASELINE_CAPTURE,
                () -> executionBaselineService.capture(plan));
        CodingAgentAdapter adapter = phase(AgentRunException.Phase.ADAPTER_RESOLUTION,
                () -> agentRegistry.resolve(request.agentType()));
        CodingAgentIdentity agent = phase(AgentRunException.Phase.AGENT_INVOCATION, adapter::identity);
        AgentExecutionRequest executionRequest = phase(AgentRunException.Phase.AGENT_INVOCATION,
                () -> new AgentExecutionRequest(agent, repositoryRoot, renderedPrompt,
                        request.timeout(), request.environmentOverrides()));
        AgentExecutionResult agentResult = phase(AgentRunException.Phase.AGENT_INVOCATION,
                () -> adapter.execute(executionRequest));

        ExecutionBaselineService.ComparisonResult comparison;
        try {
            comparison = executionBaselineService.compare(repositoryRoot, request.task(), baseline);
        } catch (RuntimeException exception) {
            throw failure(AgentRunException.Phase.EXECUTION_DELTA, exception, agentResult, null);
        }
        ExecutionChangeReport executionDelta = comparison.execution();
        VerificationReport verification;
        try {
            verification = checkService.checkExecution(comparison);
        } catch (RuntimeException exception) {
            throw failure(AgentRunException.Phase.VERIFICATION, exception, agentResult, executionDelta);
        }
        AgentRunStatus status = AgentRunStatus.decide(agentResult.status(), verification.overallStatus());
        return new AgentRunResult(repositoryRoot, request.task(), plan.taskType(), agent, plan, prompt,
                agentResult, executionDelta, verification, status);
    }

    private Path canonicalDirectory(Path repository) {
        try {
            Path canonical = repository.toRealPath();
            if (!Files.isDirectory(canonical)) {
                throw new IllegalArgumentException("Agent run repository is not a directory: " + canonical);
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to resolve agent run repository: " + repository, exception);
        }
    }

    private <T> T phase(AgentRunException.Phase phase, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            throw failure(phase, exception, null, null);
        }
    }

    private AgentRunException failure(AgentRunException.Phase phase, RuntimeException cause,
                                      AgentExecutionResult agentResult, ExecutionChangeReport executionDelta) {
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new AgentRunException(phase, "Agent run failed during " + phase + ": " + detail,
                cause, agentResult, executionDelta);
    }
}
