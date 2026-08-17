package io.agentforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentCapability;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.baseline.BaselineComparator;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PlanScope;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.RepositoryStructure;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryExecutionTest {
    @TempDir Path root;
    private static final CodingAgentIdentity CODEX = new CodingAgentIdentity(CodingAgentType.CODEX, "codex",
            Set.of(CodingAgentCapability.NON_INTERACTIVE));

    @Test
    void discoveryDeltaZeroPasses() {
        Fixture fixture = fixture(emptyDelta());
        DiscoveryOutcome outcome = fixture.orchestrator.discover(fixture.plan, request(), fixture.adapter, CODEX);
        assertThat(outcome.plan()).isSameAs(fixture.validatedPlan);
        assertThat(outcome.evidence().delta().changes()).isEmpty();
        verify(fixture.adapter).execute(any());
    }

    @Test
    void discoveryMutationFails() {
        Fixture fixture = fixture(new ExecutionChangeReport(root.toString(), "main", "main", false, "head", "head", false,
                List.of(new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.MODIFIED,
                        "A.java", null, true, true))));
        assertThatThrownBy(() -> fixture.orchestrator.discover(fixture.plan, request(), fixture.adapter, CODEX))
                .isInstanceOf(DiscoveryException.class).hasMessageContaining("modified repository");
        verify(fixture.adapter).execute(any());
    }

    private Fixture fixture(ExecutionChangeReport delta) {
        ConfigLoader config = mock(ConfigLoader.class);
        RepositoryStateCapture state = mock(RepositoryStateCapture.class);
        BaselineComparator comparator = mock(BaselineComparator.class);
        DiscoveryTrigger trigger = mock(DiscoveryTrigger.class);
        DiscoveryPromptRenderer renderer = mock(DiscoveryPromptRenderer.class);
        DiscoveryResultParser parser = mock(DiscoveryResultParser.class);
        DiscoveryScopeValidator validator = mock(DiscoveryScopeValidator.class);
        CodingAgentAdapter adapter = mock(CodingAgentAdapter.class);
        EngineeringPlan plan = plan();
        EngineeringPlan validated = mock(EngineeringPlan.class);
        RepositorySnapshot before = snapshot();
        RepositorySnapshot after = snapshot();
        var policy = new ConfigLoader().loadPolicy(root).config();
        DiscoveryResult result = new DiscoveryResult(List.of(new DiscoveryResult.Finding("e", List.of("A.java"))),
                List.of(), List.of("A.java"), List.of(), List.of(), List.of(), List.of(), DiscoveryResult.Confidence.HIGH);
        DiscoveryValidation validation = new DiscoveryValidation(DiscoveryValidation.Status.APPROVED, List.of(),
                List.of("A.java"), List.of());
        when(config.loadPolicy(root)).thenReturn(new ConfigLoader.LoadedPolicy(policy, true));
        when(trigger.decide(plan, policy)).thenReturn(new DiscoveryDecision(DiscoveryDecision.Status.REQUIRED,
                List.of(DiscoveryDecision.Reason.HIGH_SECURITY)));
        when(state.capture(root)).thenReturn(before, after);
        when(comparator.compare(before, after)).thenReturn(delta);
        when(renderer.render(any())).thenReturn("discovery prompt");
        when(adapter.execute(any())).thenReturn(new AgentExecutionResult(CODEX, 0, "{}", "", false, false,
                AgentExecutionStatus.SUCCESS, ""));
        when(parser.parse("{}", root)).thenReturn(result);
        when(validator.validate(any(), org.mockito.Mockito.eq(result), org.mockito.Mockito.eq(plan))).thenReturn(validation);
        when(plan.withDiscoveryScope(List.of("A.java"), List.of())).thenReturn(validated);
        return new Fixture(new DiscoveryOrchestrator(config, state, comparator, trigger, renderer, parser, validator),
                plan, validated, adapter);
    }

    private EngineeringPlan plan() {
        EngineeringPlan plan = mock(EngineeringPlan.class);
        RepositoryReport report = mock(RepositoryReport.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        RepositoryStructure structure = mock(RepositoryStructure.class);
        ArchitectureReport architecture = mock(ArchitectureReport.class);
        when(plan.repository()).thenReturn(report);
        when(report.repository()).thenReturn(info);
        when(info.path()).thenReturn(root);
        when(report.structure()).thenReturn(structure);
        when(structure.modules()).thenReturn(List.of("."));
        when(report.architecture()).thenReturn(architecture);
        when(architecture.analysisMode()).thenReturn(ArchitectureAnalysisMode.LIGHTWEIGHT);
        when(plan.scope()).thenReturn(new PlanScope(List.of(), List.of(), List.of()));
        when(plan.task()).thenReturn("新增密码功能");
        when(plan.taskClassification()).thenReturn(mock(io.agentforge.model.TaskClassification.class));
        return plan;
    }

    private RepositorySnapshot snapshot() { return new RepositorySnapshot(1, root.toString(), "main", "head", List.of()); }
    private ExecutionChangeReport emptyDelta() { return new ExecutionChangeReport(root.toString(), "main", "main", false, "head", "head", false, List.of()); }
    private AgentRunRequest request() { return new AgentRunRequest(root, "新增密码功能", CodingAgentType.CODEX, Duration.ofSeconds(5), Map.of()); }
    private record Fixture(DiscoveryOrchestrator orchestrator, EngineeringPlan plan, EngineeringPlan validatedPlan,
                           CodingAgentAdapter adapter) {}
}
