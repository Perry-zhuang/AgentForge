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
import io.agentforge.model.RunPreflight;
import io.agentforge.prompting.EngineeringPromptRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import io.agentforge.runtrace.RunTraceReference;
import io.agentforge.runtrace.RunTraceService;
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
    private final RunTraceService runTraceService;

    public AgentExecutionService(PlanService planService, PromptService promptService,
                                 EngineeringPromptRenderer promptRenderer,
                                 ExecutionBaselineService executionBaselineService,
                                 CodingAgentRegistry agentRegistry, CheckService checkService,
                                 RunTraceService runTraceService) {
        this.planService = planService;
        this.promptService = promptService;
        this.promptRenderer = promptRenderer;
        this.executionBaselineService = executionBaselineService;
        this.agentRegistry = agentRegistry;
        this.checkService = checkService;
        this.runTraceService = runTraceService;
    }

    public AgentRunResult execute(AgentRunRequest request) {
        if (request == null) {
            throw failure(AgentRunException.Phase.REPOSITORY_VALIDATION,
                    new IllegalArgumentException("Agent run request is required"), null, null);
        }
        Path requestedRoot = phase(AgentRunException.Phase.REPOSITORY_VALIDATION,
                () -> canonicalDirectory(request.repositoryRoot()));
        EngineeringPlan plan = phase(AgentRunException.Phase.PLAN,
                () -> planService.plan(new EngineeringPlanRequest(requestedRoot, request.task(),
                        request.taskTypeOverride())));
        Path repositoryRoot = plan.repository().repository().path();
        RunPreflight preflight = plan.runPreflight();
        if (!preflight.allowed()) throw new RunPreflightException(plan, preflight);
        EngineeringPrompt prompt = phase(AgentRunException.Phase.PROMPT,
                () -> promptService.fromPlan(plan));
        String renderedPrompt = phase(AgentRunException.Phase.PROMPT,
                () -> promptRenderer.render(prompt));
        ExecutionBaseline baseline = phase(AgentRunException.Phase.BASELINE_CAPTURE,
                () -> executionBaselineService.capture(plan));
        CodingAgentAdapter adapter = phase(AgentRunException.Phase.ADAPTER_RESOLUTION,
                () -> agentRegistry.resolve(request.agentType()));
        CodingAgentIdentity agent = phase(AgentRunException.Phase.AGENT_INVOCATION, adapter::identity);
        RunTraceReference trace = phase(AgentRunException.Phase.TRACE_PREPARE,
                () -> runTraceService.prepare(request, plan, renderedPrompt, baseline, agent));
        AgentExecutionRequest executionRequest = phase(AgentRunException.Phase.AGENT_INVOCATION,
                () -> new AgentExecutionRequest(agent, repositoryRoot, renderedPrompt,
                        request.timeout(), request.environmentOverrides()));
        AgentExecutionResult agentResult;
        try {
            agentResult = adapter.execute(executionRequest);
        } catch (RuntimeException exception) {
            AgentRunException failure = failure(AgentRunException.Phase.AGENT_INVOCATION, exception, null, null, trace);
            runTraceService.fail(trace, failure.phase(), failure.getMessage(), null, null);
            throw failure;
        }
        try {
            runTraceService.persistAgentResult(trace, agentResult);
        } catch (RuntimeException exception) {
            AgentRunException failure = failure(AgentRunException.Phase.TRACE_PERSISTENCE, exception,
                    agentResult, null, trace);
            safelyFailTrace(trace, failure, agentResult, null);
            throw failure;
        }

        ExecutionBaselineService.ComparisonResult comparison;
        try {
            comparison = executionBaselineService.compare(repositoryRoot, request.task(), baseline);
        } catch (RuntimeException exception) {
            AgentRunException failure = failure(AgentRunException.Phase.EXECUTION_DELTA, exception,
                    agentResult, null, trace);
            safelyFailTrace(trace, failure, agentResult, null);
            throw failure;
        }
        ExecutionChangeReport executionDelta = comparison.execution();
        VerificationReport verification;
        try {
            verification = checkService.checkExecution(comparison);
        } catch (RuntimeException exception) {
            AgentRunException failure = failure(AgentRunException.Phase.VERIFICATION, exception,
                    agentResult, executionDelta, trace);
            safelyFailTrace(trace, failure, agentResult, executionDelta);
            throw failure;
        }
        AgentRunStatus status = AgentRunStatus.decide(agentResult.status(), verification.overallStatus());
        AgentRunResult provisional = new AgentRunResult(repositoryRoot, request.task(), plan.taskType(), agent, plan,
                prompt, agentResult, executionDelta, verification, status, trace);
        try {
            RunTraceReference completed = runTraceService.complete(trace, provisional);
            return new AgentRunResult(repositoryRoot, request.task(), plan.taskType(), agent, plan, prompt,
                    agentResult, executionDelta, verification, status, completed);
        } catch (RuntimeException exception) {
            AgentRunException failure = failure(AgentRunException.Phase.TRACE_PERSISTENCE, exception,
                    agentResult, executionDelta, trace);
            safelyFailTrace(trace, failure, agentResult, executionDelta);
            throw failure;
        }
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
        return failure(phase, cause, agentResult, executionDelta, null);
    }

    private AgentRunException failure(AgentRunException.Phase phase, RuntimeException cause,
                                      AgentExecutionResult agentResult, ExecutionChangeReport executionDelta,
                                      RunTraceReference trace) {
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new AgentRunException(phase, "Agent run failed during " + phase + ": " + detail,
                cause, agentResult, executionDelta, trace);
    }

    private void safelyFailTrace(RunTraceReference trace, AgentRunException failure,
                                 AgentExecutionResult agentResult, ExecutionChangeReport delta) {
        try {
            runTraceService.fail(trace, failure.phase(), failure.getMessage(), agentResult, delta);
        } catch (RuntimeException ignored) {
            failure.addSuppressed(ignored);
        }
    }
}
