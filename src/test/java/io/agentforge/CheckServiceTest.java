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
import io.agentforge.application.ExecutionBaselineService;
import io.agentforge.application.PlanService;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.RiskLevel;
import io.agentforge.model.RiskReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationRequest;
import io.agentforge.model.VerificationReport;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.RepositoryScanner;
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
        ExecutionBaselineService executionBaselineService = mock(ExecutionBaselineService.class);
        RepositoryScanner repositoryScanner = mock(RepositoryScanner.class);
        ArchitectureScanner architectureScanner = mock(ArchitectureScanner.class);
        PolicyEngine policyEngine = mock(PolicyEngine.class);
        ExecutionArchitectureAttributor architectureAttributor = mock(ExecutionArchitectureAttributor.class);
        CheckService service = new CheckService(planService, diffService, executionBaselineService,
                repositoryScanner, architectureScanner, policyEngine, architectureAttributor, new CheckEngine());

        var report = service.check(new VerificationRequest(Path.of("."), "修复任务异常"));

        assertThat(report.plan()).isSameAs(plan);
        assertThat(report.diffAudit()).isSameAs(diff);
        verify(planService).plan(planRequest);
        verify(diffService).audit(plan.repository().repository().path());
        verifyNoMoreInteractions(planService, diffService);
        verifyNoInteractions(executionBaselineService, repositoryScanner, architectureScanner,
                policyEngine, architectureAttributor);
    }

    @Test
    void executionModeUsesEachExecutionFactSourceOnceAndNeverAuditsWholeWorkingTree() {
        PlanService planService = mock(PlanService.class);
        DiffAuditService diffService = mock(DiffAuditService.class);
        ExecutionBaselineService executionBaselineService = mock(ExecutionBaselineService.class);
        RepositoryScanner repositoryScanner = mock(RepositoryScanner.class);
        ArchitectureScanner architectureScanner = mock(ArchitectureScanner.class);
        PolicyEngine policyEngine = mock(PolicyEngine.class);
        ExecutionArchitectureAttributor architectureAttributor = mock(ExecutionArchitectureAttributor.class);
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        Path root = plan.repository().repository().path();
        Path baselineFile = Path.of("/tmp/check-before.json");
        ExecutionChangeReport execution = new ExecutionChangeReport(root.toString(), "main", "main", false,
                plan.repository().repository().head(), plan.repository().repository().head(), false, List.of());
        PolicyConfig.ChangePolicy changePolicy = new PolicyConfig.ChangePolicy(20, List.of(), List.of());
        EngineeringBoundarySnapshot boundary = new EngineeringBoundarySnapshot(
                "修复任务异常", TaskType.BUGFIX,
                plan.scope().primary().stream().map(file -> file.path()).toList(),
                plan.scope().secondary().stream().map(file -> file.path()).toList(),
                plan.scope().reference().stream().map(file -> file.path()).toList(),
                changePolicy, true, ArchitectureAnalysisMode.LIGHTWEIGHT, null);
        RepositorySnapshot current = new RepositorySnapshot(RepositorySnapshot.CURRENT_SCHEMA_VERSION,
                root.toString(), "main", plan.repository().repository().head(), List.of());
        ArchitectureReport architecture = plan.repository().architecture();
        ExecutionArchitectureAttributor.Result attribution =
                new ExecutionArchitectureAttributor.Result(architecture, List.of());
        PolicyReport policy = new PolicyReport(List.of());
        List<io.agentforge.model.ChangedFile> policyChanges = List.of();
        when(executionBaselineService.compare(Path.of("."), "修复任务异常", baselineFile)).thenReturn(
                new ExecutionBaselineService.ComparisonResult(boundary, current, execution));
        when(architectureAttributor.attribute(architecture, execution)).thenReturn(attribution);
        when(policyEngine.evaluate(policyChanges, changePolicy, architecture)).thenReturn(policy);
        CheckService service = new CheckService(planService, diffService, executionBaselineService,
                repositoryScanner, architectureScanner, policyEngine, architectureAttributor, new CheckEngine());

        VerificationReport report = service.check(new VerificationRequest(
                Path.of("."), "修复任务异常", baselineFile));

        assertThat(report.baseline()).isEqualTo(VerificationReport.Baseline.EXECUTION_DELTA);
        assertThat(report.plan()).isNull();
        assertThat(report.executionBoundary()).isSameAs(boundary);
        assertThat(report.diffAudit()).isNull();
        assertThat(report.changeSet().executionChangeReport()).containsSame(execution);
        verify(executionBaselineService).compare(Path.of("."), "修复任务异常", baselineFile);
        verify(architectureAttributor).attribute(architecture, execution);
        verify(policyEngine).evaluate(policyChanges, changePolicy, architecture);
        verifyNoInteractions(planService, diffService, repositoryScanner, architectureScanner);
        verifyNoMoreInteractions(executionBaselineService, architectureAttributor, policyEngine);
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
