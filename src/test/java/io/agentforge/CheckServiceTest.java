package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentforge.application.BaselineService;
import io.agentforge.application.CheckService;
import io.agentforge.application.DiffAuditService;
import io.agentforge.application.PlanService;
import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RiskLevel;
import io.agentforge.model.RiskReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationRequest;
import io.agentforge.model.VerificationReport;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.verification.CheckEngine;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckServiceTest {
    @Test
    void callsPlanAndDiffExactlyOnceAndVerifiesOnlyThoseFacts() {
        PlanService planService = mock(PlanService.class);
        DiffAuditService diffService = mock(DiffAuditService.class);
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        DiffAuditReport diff = emptyDiff(plan);
        EngineeringPlanRequest planRequest = new EngineeringPlanRequest(Path.of("."), "修复任务异常");
        when(planService.plan(planRequest)).thenReturn(plan);
        when(diffService.audit(plan.repository().repository().path())).thenReturn(diff);
        BaselineService baselineService = mock(BaselineService.class);
        ConfigLoader configLoader = mock(ConfigLoader.class);
        PolicyEngine policyEngine = mock(PolicyEngine.class);
        ExecutionArchitectureAttributor architectureAttributor = mock(ExecutionArchitectureAttributor.class);
        CheckService service = new CheckService(planService, diffService, baselineService, configLoader,
                policyEngine, architectureAttributor, new CheckEngine());

        var report = service.check(new VerificationRequest(Path.of("."), "修复任务异常"));

        assertThat(report.plan()).isSameAs(plan);
        assertThat(report.diffAudit()).isSameAs(diff);
        verify(planService).plan(planRequest);
        verify(diffService).audit(plan.repository().repository().path());
        verifyNoMoreInteractions(planService, diffService);
        verifyNoInteractions(baselineService, configLoader, policyEngine, architectureAttributor);
    }

    @Test
    void executionModeUsesEachExecutionFactSourceOnceAndNeverAuditsWholeWorkingTree() {
        PlanService planService = mock(PlanService.class);
        DiffAuditService diffService = mock(DiffAuditService.class);
        BaselineService baselineService = mock(BaselineService.class);
        ConfigLoader configLoader = mock(ConfigLoader.class);
        PolicyEngine policyEngine = mock(PolicyEngine.class);
        ExecutionArchitectureAttributor architectureAttributor = mock(ExecutionArchitectureAttributor.class);
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        Path root = plan.repository().repository().path();
        Path baselineFile = Path.of("/tmp/check-before.json");
        ExecutionChangeReport execution = new ExecutionChangeReport(root.toString(), "main", "main", false,
                plan.repository().repository().head(), plan.repository().repository().head(), false, List.of());
        ConfigLoader.LoadedPolicy loadedPolicy = new ConfigLoader().loadPolicy(root);
        ArchitectureReport architecture = plan.repository().architecture();
        ExecutionArchitectureAttributor.Result attribution =
                new ExecutionArchitectureAttributor.Result(architecture, List.of());
        PolicyReport policy = new PolicyReport(List.of());
        EngineeringPlanRequest planRequest = new EngineeringPlanRequest(Path.of("."), "修复任务异常");
        List<io.agentforge.model.ChangedFile> policyChanges = List.of();
        when(planService.plan(planRequest)).thenReturn(plan);
        when(baselineService.compare(root, baselineFile)).thenReturn(execution);
        when(configLoader.loadPolicy(root)).thenReturn(loadedPolicy);
        when(architectureAttributor.attribute(architecture, execution)).thenReturn(attribution);
        when(policyEngine.evaluate(policyChanges, loadedPolicy.config(), architecture)).thenReturn(policy);
        CheckService service = new CheckService(planService, diffService, baselineService, configLoader,
                policyEngine, architectureAttributor, new CheckEngine());

        VerificationReport report = service.check(new VerificationRequest(
                Path.of("."), "修复任务异常", baselineFile));

        assertThat(report.baseline()).isEqualTo(VerificationReport.Baseline.EXECUTION_DELTA);
        assertThat(report.diffAudit()).isNull();
        assertThat(report.changeSet().executionChangeReport()).containsSame(execution);
        verify(planService).plan(planRequest);
        verify(baselineService).compare(root, baselineFile);
        verify(configLoader).loadPolicy(root);
        verify(architectureAttributor).attribute(architecture, execution);
        verify(policyEngine).evaluate(policyChanges, loadedPolicy.config(), architecture);
        verifyNoInteractions(diffService);
        verifyNoMoreInteractions(planService, baselineService, configLoader, architectureAttributor, policyEngine);
    }

    private DiffAuditReport emptyDiff(EngineeringPlan plan) {
        RepositoryInfo source = plan.repository().repository();
        RepositoryInfo repository = new RepositoryInfo(source.path(), true, source.path(), source.branch(),
                source.head(), false, List.of());
        ChangeReport change = new ChangeReport(repository, List.of(), Set.of(),
                false, false, false, false, false, false, List.of());
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(),
                Map.of(), List.of(), List.of(), false, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        return new DiffAuditReport(change, new PolicyReport(List.of()),
                new ScoreBreakdown("Minimal Change", 100, List.of()),
                new RiskReport(0, RiskLevel.LOW, List.of()), architecture, false);
    }
}
