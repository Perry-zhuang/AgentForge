package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentforge.agent.AgentExecutionRequest;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentCapability;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentRegistry;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.application.AgentExecutionService;
import io.agentforge.application.AgentRunException;
import io.agentforge.application.CheckService;
import io.agentforge.application.ExecutionBaselineService;
import io.agentforge.application.PlanService;
import io.agentforge.application.PromptService;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationChangeSet;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import io.agentforge.model.ViolationSeverity;
import io.agentforge.model.TaskClassification;
import io.agentforge.model.TaskTypeConfidence;
import io.agentforge.model.MutationMode;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.ClassificationSource;
import io.agentforge.model.ClassificationSignal;
import io.agentforge.model.RunPreflight;
import io.agentforge.application.RunPreflightException;
import io.agentforge.prompting.EngineeringPromptRenderer;
import io.agentforge.prompting.PromptEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import io.agentforge.runtrace.RunTraceReference;
import io.agentforge.runtrace.RunTraceService;

class AgentExecutionServiceTest {
    private static final CodingAgentIdentity CODEX = new CodingAgentIdentity(CodingAgentType.CODEX, "codex",
            Set.of(CodingAgentCapability.NON_INTERACTIVE));

    @TempDir
    Path repository;

    @Test
    void executesTheFrozenLifecycleInOrderWithOnePlanPromptBaselineAndAdapterCall() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        AgentRunRequest request = request();

        AgentRunResult result = fixture.service.execute(request);

        InOrder order = inOrder(fixture.planService, fixture.promptService, fixture.renderer,
                fixture.baselineService, fixture.registry, fixture.adapter, fixture.checkService);
        order.verify(fixture.planService).plan(new EngineeringPlanRequest(canonicalRepository(), request.task()));
        order.verify(fixture.promptService).fromPlan(fixture.plan);
        order.verify(fixture.renderer).render(fixture.prompt);
        order.verify(fixture.baselineService).capture(fixture.plan);
        order.verify(fixture.registry).resolve(CodingAgentType.CODEX);
        order.verify(fixture.adapter).identity();
        ArgumentCaptor<AgentExecutionRequest> executionRequest = ArgumentCaptor.forClass(AgentExecutionRequest.class);
        order.verify(fixture.adapter).execute(executionRequest.capture());
        order.verify(fixture.baselineService).compare(canonicalRepository(), request.task(), fixture.baseline);
        order.verify(fixture.checkService).checkExecution(fixture.comparison);
        verifyNoMoreInteractions(fixture.planService, fixture.promptService, fixture.renderer, fixture.registry,
                fixture.adapter, fixture.checkService);

        assertThat(executionRequest.getValue().repositoryRoot()).isEqualTo(canonicalRepository());
        assertThat(executionRequest.getValue().renderedPrompt()).isEqualTo("rendered prompt\nunchanged");
        assertThat(executionRequest.getValue().timeout()).isEqualTo(request.timeout());
        assertThat(executionRequest.getValue().environmentOverrides()).containsExactlyEntriesOf(
                request.environmentOverrides());
        assertThat(result.plan()).isSameAs(fixture.plan);
        assertThat(result.prompt()).isSameAs(fixture.prompt);
        assertThat(result.agentExecutionResult()).isSameAs(fixture.agentResult);
        assertThat(result.executionChangeReport()).isSameAs(fixture.execution);
        assertThat(result.verificationReport()).isSameAs(fixture.verification);
        assertThat(result.status()).isEqualTo(AgentRunStatus.PASS);
    }

    @Test
    void processFailureStillComputesDeltaAndVerificationAndRetainsBothFacts() {
        Fixture fixture = fixture(AgentExecutionStatus.FAILED, VerificationStatus.FAIL);

        AgentRunResult result = fixture.service.execute(request());

        assertThat(result.status()).isEqualTo(AgentRunStatus.AGENT_FAILED);
        assertThat(result.agentExecutionResult().processExitCode()).isEqualTo(7);
        assertThat(result.executionChangeReport()).isSameAs(fixture.execution);
        assertThat(result.verificationReport().overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(result.verificationReport().findings()).extracting(VerificationFinding::code)
                .containsExactly("OUT_OF_SCOPE_MODIFICATION");
        verify(fixture.baselineService).compare(canonicalRepository(), request().task(), fixture.baseline);
        verify(fixture.checkService).checkExecution(fixture.comparison);
        verify(fixture.adapter).execute(any());
    }

    @Test
    void everyProcessStatusMapsDeterministicallyWhileVerificationRemainsAvailable() {
        assertStatus(AgentExecutionStatus.SUCCESS, VerificationStatus.WARNING, AgentRunStatus.WARNING);
        assertStatus(AgentExecutionStatus.SUCCESS, VerificationStatus.FAIL, AgentRunStatus.VERIFICATION_FAILED);
        assertStatus(AgentExecutionStatus.FAILED, VerificationStatus.PASS, AgentRunStatus.AGENT_FAILED);
        assertStatus(AgentExecutionStatus.TIMED_OUT, VerificationStatus.FAIL, AgentRunStatus.AGENT_TIMED_OUT);
        assertStatus(AgentExecutionStatus.INTERRUPTED, VerificationStatus.WARNING, AgentRunStatus.AGENT_INTERRUPTED);
        assertStatus(AgentExecutionStatus.START_FAILED, VerificationStatus.FAIL, AgentRunStatus.AGENT_START_FAILED);
    }

    @Test
    void promptFailureStopsBeforeBaselineAndAdapter() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        when(fixture.promptService.fromPlan(fixture.plan)).thenThrow(new IllegalStateException("prompt failed"));

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(AgentRunException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(AgentRunException.Phase.PROMPT);
                    assertThat(exception.agentResult()).isEmpty();
                });

        verify(fixture.baselineService, never()).capture(any(EngineeringPlan.class));
        verifyNoInteractions(fixture.registry, fixture.adapter, fixture.checkService);
    }

    @Test
    void baselineFailureStopsBeforeAdapterResolution() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        when(fixture.baselineService.capture(fixture.plan)).thenThrow(new IllegalStateException("baseline failed"));

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(AgentRunException.class,
                        exception -> assertThat(exception.phase()).isEqualTo(AgentRunException.Phase.BASELINE_CAPTURE));

        verifyNoInteractions(fixture.registry, fixture.adapter, fixture.checkService);
    }

    @Test
    void blockedPreflightStopsBeforePromptBaselineTraceAndAdapter() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        TaskClassification classification = new TaskClassification(TaskType.UNKNOWN, TaskType.UNKNOWN,
                TaskTypeConfidence.LOW, MutationMode.SOURCE_WRITE, SecurityImpact.HIGH,
                ClassificationSource.DETECTED,
                List.of(ClassificationSignal.SOURCE_WRITE, ClassificationSignal.PASSWORD), false);
        EngineeringPlan blocked = withClassification(fixture.plan, classification,
                new RunPreflight(RunPreflight.Status.BLOCKED,
                        RunPreflight.Reason.UNKNOWN_HIGH_SECURITY, ""));
        when(fixture.planService.plan(any())).thenReturn(blocked);

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(RunPreflightException.class, exception -> {
                    assertThat(exception.plan()).isSameAs(blocked);
                    assertThat(exception.preflight().reason()).isEqualTo(
                            RunPreflight.Reason.UNKNOWN_HIGH_SECURITY);
                });

        verifyNoInteractions(fixture.promptService, fixture.renderer, fixture.baselineService,
                fixture.registry, fixture.adapter, fixture.checkService, fixture.runTraceService);
    }

    @Test
    void tracePreparationFailureStopsBeforeAdapterInvocation() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        when(fixture.runTraceService.prepare(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("archive unavailable"));

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(AgentRunException.class,
                        exception -> assertThat(exception.phase()).isEqualTo(AgentRunException.Phase.TRACE_PREPARE));

        verify(fixture.adapter, never()).execute(any());
        verify(fixture.baselineService, never()).compare(any(Path.class), any(String.class),
                any(ExecutionBaseline.class));
    }

    @Test
    void invalidRepositoryNeverPlansOrInvokesAdapter() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        AgentRunRequest request = new AgentRunRequest(repository.resolve("missing"), "修复任务异常",
                CodingAgentType.CODEX, Duration.ofSeconds(4), Map.of());

        assertThatThrownBy(() -> fixture.service.execute(request))
                .isInstanceOfSatisfying(AgentRunException.class,
                        exception -> assertThat(exception.phase())
                                .isEqualTo(AgentRunException.Phase.REPOSITORY_VALIDATION));

        verifyNoInteractions(fixture.planService, fixture.promptService, fixture.renderer,
                fixture.baselineService, fixture.registry, fixture.adapter, fixture.checkService);
    }

    @Test
    void verificationInfrastructureFailureRetainsProcessAndDeltaFacts() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        when(fixture.checkService.checkExecution(fixture.comparison))
                .thenThrow(new IllegalStateException("check unavailable"));

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(AgentRunException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(AgentRunException.Phase.VERIFICATION);
                    assertThat(exception.agentResult()).containsSame(fixture.agentResult);
                    assertThat(exception.executionDelta()).containsSame(fixture.execution);
                    assertThat(exception.runTrace()).isPresent();
                });
        verify(fixture.runTraceService).fail(any(), any(), any(), any(), any());
    }

    @Test
    void deltaInfrastructureFailureRetainsProcessFacts() {
        Fixture fixture = fixture(AgentExecutionStatus.SUCCESS, VerificationStatus.PASS);
        when(fixture.baselineService.compare(canonicalRepository(), request().task(), fixture.baseline))
                .thenThrow(new IllegalStateException("delta unavailable"));

        assertThatThrownBy(() -> fixture.service.execute(request()))
                .isInstanceOfSatisfying(AgentRunException.class, exception -> {
                    assertThat(exception.phase()).isEqualTo(AgentRunException.Phase.EXECUTION_DELTA);
                    assertThat(exception.agentResult()).containsSame(fixture.agentResult);
                    assertThat(exception.executionDelta()).isEmpty();
                });
        verify(fixture.checkService, never()).checkExecution(any());
    }

    private void assertStatus(AgentExecutionStatus process, VerificationStatus verification,
                              AgentRunStatus expected) {
        Fixture fixture = fixture(process, verification);
        AgentRunResult result = fixture.service.execute(request());
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.verificationReport().overallStatus()).isEqualTo(verification);
        verify(fixture.baselineService).compare(canonicalRepository(), request().task(), fixture.baseline);
        verify(fixture.checkService).checkExecution(fixture.comparison);
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(repository, "修复任务异常", CodingAgentType.CODEX,
                Duration.ofSeconds(4), Map.of("AGENTFORGE_TEST", "value"));
    }

    private Fixture fixture(AgentExecutionStatus processStatus, VerificationStatus verificationStatus) {
        PlanService planService = mock(PlanService.class);
        PromptService promptService = mock(PromptService.class);
        EngineeringPromptRenderer renderer = mock(EngineeringPromptRenderer.class);
        ExecutionBaselineService baselineService = mock(ExecutionBaselineService.class);
        CodingAgentRegistry registry = mock(CodingAgentRegistry.class);
        CodingAgentAdapter adapter = mock(CodingAgentAdapter.class);
        CheckService checkService = mock(CheckService.class);
        RunTraceService runTraceService = mock(RunTraceService.class);
        Path root = canonicalRepository();
        EngineeringPlan plan = atRepository(PromptTestFixtures.plan(TaskType.BUGFIX), root);
        EngineeringPrompt prompt = new PromptEngine().create(plan);
        EngineeringBoundarySnapshot boundary = boundary(plan);
        RepositorySnapshot snapshot = new RepositorySnapshot(RepositorySnapshot.CURRENT_SCHEMA_VERSION,
                root.toString(), "main", plan.gitBaseline().head(), List.of());
        ExecutionBaseline baseline = new ExecutionBaseline(ExecutionBaseline.CURRENT_SCHEMA_VERSION,
                snapshot, boundary);
        ExecutionChangeReport execution = new ExecutionChangeReport(root.toString(),
                "main", "main", false, plan.gitBaseline().head(), plan.gitBaseline().head(), false,
                verificationStatus == VerificationStatus.FAIL
                        ? List.of(new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.ADDED,
                                "outside.txt", null, true, true)) : List.of());
        ExecutionBaselineService.ComparisonResult comparison =
                new ExecutionBaselineService.ComparisonResult(boundary, snapshot, execution);
        VerificationReport verification = verification(plan, boundary, snapshot, execution, verificationStatus);
        AgentExecutionResult agentResult = processResult(processStatus);

        when(planService.plan(any())).thenReturn(plan);
        when(promptService.fromPlan(plan)).thenReturn(prompt);
        when(renderer.render(prompt)).thenReturn("rendered prompt\nunchanged");
        when(baselineService.capture(plan)).thenReturn(baseline);
        when(registry.resolve(CodingAgentType.CODEX)).thenReturn(adapter);
        when(adapter.identity()).thenReturn(CODEX);
        when(adapter.execute(any())).thenReturn(agentResult);
        RunTraceReference trace = new RunTraceReference("test-run", Path.of("/tmp/test-run"),
                Path.of("/tmp/test-run/report.md"), null);
        when(runTraceService.prepare(any(), any(), any(), any(), any())).thenReturn(trace);
        when(runTraceService.complete(any(), any())).thenReturn(trace);
        when(baselineService.compare(root, plan.task(), baseline)).thenReturn(comparison);
        when(checkService.checkExecution(comparison)).thenReturn(verification);
        AgentExecutionService service = new AgentExecutionService(planService, promptService, renderer,
                baselineService, registry, checkService, runTraceService);
        return new Fixture(service, planService, promptService, renderer, baselineService, registry, adapter,
                checkService, runTraceService, plan, prompt, baseline, comparison, execution, verification, agentResult);
    }

    private EngineeringPlan atRepository(EngineeringPlan source, Path root) {
        RepositoryInfo info = source.repository().repository();
        RepositoryInfo replacedInfo = new RepositoryInfo(root, true, root, info.branch(), info.head(),
                info.dirty(), info.warnings());
        RepositoryReport report = new RepositoryReport(replacedInfo, source.repository().technology(),
                source.repository().structure(), source.repository().context(), source.repository().architecture());
        return new EngineeringPlan(source.task(), source.taskType(), source.taskTypeSignal(), source.confidence(),
                report, source.gitBaseline(), source.scope(), source.scopeOverlap(), source.steps(),
                source.constraints(), source.estimatedChangeScope(), source.preImplementationRisk(), source.tests(),
                source.acceptanceCriteria(), source.defaultPolicyUsed(), source.reliableScope(), source.warnings());
    }

    private EngineeringPlan withClassification(EngineeringPlan source, TaskClassification classification,
                                               RunPreflight preflight) {
        return new EngineeringPlan(source.task(), classification.effectiveType(), "SOURCE_WRITE", classification,
                preflight, source.confidence(), source.repository(), source.gitBaseline(), source.scope(),
                source.scopeOverlap(), source.steps(), source.constraints(), source.estimatedChangeScope(),
                source.preImplementationRisk(), source.tests(), source.acceptanceCriteria(),
                source.defaultPolicyUsed(), source.reliableScope(), source.warnings());
    }

    private Path canonicalRepository() {
        try {
            return repository.toRealPath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private EngineeringBoundarySnapshot boundary(EngineeringPlan plan) {
        return new EngineeringBoundarySnapshot(plan.task(), plan.taskType(),
                plan.scope().primary().stream().map(file -> file.path()).toList(),
                plan.scope().secondary().stream().map(file -> file.path()).toList(),
                plan.scope().reference().stream().map(file -> file.path()).toList(),
                new PolicyConfig.ChangePolicy(20, List.of(), List.of()), true,
                ArchitectureAnalysisMode.LIGHTWEIGHT, null);
    }

    private VerificationReport verification(EngineeringPlan plan, EngineeringBoundarySnapshot boundary,
                                            RepositorySnapshot snapshot, ExecutionChangeReport execution,
                                            VerificationStatus status) {
        List<VerificationFinding> findings = status == VerificationStatus.FAIL
                ? List.of(new VerificationFinding(VerificationFinding.Category.SCOPE, ViolationSeverity.ERROR,
                        "OUT_OF_SCOPE_MODIFICATION", "outside.txt", "outside")) : List.of();
        VerificationStatus scope = status == VerificationStatus.FAIL ? VerificationStatus.FAIL : status;
        VerificationChangeSet facts = VerificationChangeSet.executionDelta(
                new RepositoryInfo(Path.of(snapshot.repositoryRoot()), true, Path.of(snapshot.repositoryRoot()),
                        snapshot.branch(), snapshot.head(), false, List.of()),
                execution, new io.agentforge.model.PolicyReport(List.of()), plan.repository().architecture(),
                true, List.of());
        return new VerificationReport(null, boundary, null, VerificationReport.Baseline.EXECUTION_DELTA,
                scope, VerificationStatus.PASS, VerificationStatus.PASS, VerificationStatus.PASS,
                status, findings, List.of(), facts);
    }

    private AgentExecutionResult processResult(AgentExecutionStatus status) {
        Integer exit = switch (status) {
            case SUCCESS -> 0;
            case FAILED -> 7;
            case TIMED_OUT, INTERRUPTED -> 143;
            case START_FAILED -> null;
        };
        return new AgentExecutionResult(CODEX, exit, "stdout", "stderr",
                status == AgentExecutionStatus.TIMED_OUT, status == AgentExecutionStatus.INTERRUPTED,
                status, status == AgentExecutionStatus.SUCCESS ? "" : status.name());
    }

    private record Fixture(
            AgentExecutionService service,
            PlanService planService,
            PromptService promptService,
            EngineeringPromptRenderer renderer,
            ExecutionBaselineService baselineService,
            CodingAgentRegistry registry,
            CodingAgentAdapter adapter,
            CheckService checkService,
            RunTraceService runTraceService,
            EngineeringPlan plan,
            EngineeringPrompt prompt,
            ExecutionBaseline baseline,
            ExecutionBaselineService.ComparisonResult comparison,
            ExecutionChangeReport execution,
            VerificationReport verification,
            AgentExecutionResult agentResult
    ) {}
}
