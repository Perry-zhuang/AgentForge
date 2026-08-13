package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentforge.application.InspectService;
import io.agentforge.application.PlanService;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.model.TaskInspectionRequest;
import io.agentforge.planning.PlanEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PlanServiceTest {
    @Test
    void delegatesToInspectOnceAndDoesNotRequireGitForPlanning(@TempDir Path root) {
        InspectService inspectService = mock(InspectService.class);
        ConfigLoader configLoader = mock(ConfigLoader.class);
        GitChangeScanner gitChangeScanner = mock(GitChangeScanner.class);
        PlanEngine planEngine = mock(PlanEngine.class);
        TaskInspectionReport inspection = mock(TaskInspectionReport.class);
        RepositoryReport repositoryReport = mock(RepositoryReport.class);
        RepositoryInfo repository = new RepositoryInfo(root, false, null, "N/A", "N/A", false, List.of());
        EngineeringPlan expected = mock(EngineeringPlan.class);
        ConfigLoader.LoadedPolicy policy = new ConfigLoader().loadPolicy(root);
        when(inspection.request()).thenReturn(new TaskInspectionRequest(root, "分析历史告警流程", 10));
        when(inspection.repositoryReport()).thenReturn(repositoryReport);
        when(repositoryReport.repository()).thenReturn(repository);
        when(inspectService.inspect(any())).thenReturn(inspection);
        when(configLoader.loadPolicy(root)).thenReturn(policy);
        when(configLoader.loadArchitecture(root)).thenReturn(Optional.empty());
        when(planEngine.create(same(inspection), same(policy.config()), eq(true), eq(Optional.empty()),
                eq(Optional.empty()), isNull())).thenReturn(expected);
        PlanService service = new PlanService(inspectService, configLoader, gitChangeScanner, planEngine);

        EngineeringPlan actual = service.plan(new EngineeringPlanRequest(root, "分析历史告警流程"));

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<TaskInspectionRequest> request = ArgumentCaptor.forClass(TaskInspectionRequest.class);
        verify(inspectService).inspect(request.capture());
        assertThat(request.getValue().path()).isEqualTo(root);
        assertThat(request.getValue().task()).isEqualTo("分析历史告警流程");
        assertThat(request.getValue().limit()).isEqualTo(10);
        verify(gitChangeScanner, never()).scan(any());
        verify(planEngine).create(same(inspection), same(policy.config()), eq(true), eq(Optional.empty()),
                eq(Optional.empty()), isNull());
    }
}
