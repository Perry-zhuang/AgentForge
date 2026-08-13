package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentforge.application.ExecutionBaselineService;
import io.agentforge.application.PlanService;
import io.agentforge.baseline.BaselineComparator;
import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.TaskType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionBaselineServiceTest {
    @Test
    void inMemoryCaptureAndCompareUseTheProvidedPlanWithoutPersistenceOrReplanning(@TempDir Path temp)
            throws Exception {
        Path root = temp.toRealPath();
        EngineeringPlan plan = atRepository(PromptTestFixtures.plan(TaskType.BUGFIX), root);
        PlanService planService = mock(PlanService.class);
        RepositoryStateCapture stateCapture = mock(RepositoryStateCapture.class);
        RepositorySnapshotStore store = mock(RepositorySnapshotStore.class);
        BaselineComparator comparator = mock(BaselineComparator.class);
        RepositorySnapshot before = snapshot(root, plan.gitBaseline().head());
        RepositorySnapshot after = snapshot(root, plan.gitBaseline().head());
        ExecutionChangeReport delta = new ExecutionChangeReport(root.toString(), "main", "main", false,
                plan.gitBaseline().head(), plan.gitBaseline().head(), false, List.of());
        when(stateCapture.capture(root)).thenReturn(before, after);
        when(comparator.compare(before, after)).thenReturn(delta);
        ExecutionBaselineService service = new ExecutionBaselineService(planService, new ConfigLoader(),
                stateCapture, store, comparator);

        var baseline = service.capture(plan);
        var comparison = service.compare(root, plan.task(), baseline);

        assertThat(baseline.engineeringBoundary().task()).isEqualTo(plan.task());
        assertThat(baseline.engineeringBoundary().taskType()).isEqualTo(plan.taskType());
        assertThat(baseline.engineeringBoundary().primaryPaths())
                .containsExactlyElementsOf(plan.scope().primary().stream().map(file -> file.path()).sorted().toList());
        assertThat(baseline.repositorySnapshot()).isSameAs(before);
        assertThat(comparison.boundary()).isSameAs(baseline.engineeringBoundary());
        assertThat(comparison.execution()).isSameAs(delta);
        verify(stateCapture, times(2)).capture(root);
        verify(comparator).compare(before, after);
        verifyNoInteractions(planService, store);
    }

    private RepositorySnapshot snapshot(Path root, String head) {
        return new RepositorySnapshot(RepositorySnapshot.CURRENT_SCHEMA_VERSION, root.toString(),
                "main", head, List.of());
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
}
