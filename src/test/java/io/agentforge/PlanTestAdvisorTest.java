package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.ConfidenceProfile;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.FileResponsibility;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanTest;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.TaskType;
import io.agentforge.planning.PlanTestAdvisor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanTestAdvisorTest {
    private final PlanTestAdvisor advisor = new PlanTestAdvisor();

    @Test
    void ordersBugfixSchedulerIntegrationAndLimitedTestGuidance() {
        RelevantFile task = file("TimerTaskImpl", FileResponsibility.SERVICE);
        RepositoryReport repository = repository(List.of(task), Map.of(), 1_312, 3);
        DependencyEdge flow = new DependencyEdge("demo.TimerTaskImpl", "demo.AlertService",
                JavaRole.SERVICE, JavaRole.SERVICE);

        List<String> actions = descriptions(advisor.advise(repository,
                new PlanScope(List.of(task), List.of(), List.of()), List.of(flow),
                TaskType.BUGFIX, true, 0.10));

        assertThat(actions).containsExactly(
                "Run Maven tests.",
                "Add a regression test when practical; otherwise reproduce and verify the failure manually.",
                "Verify scheduled/task execution path and trigger behavior.",
                "Verify upstream and downstream integration contracts.",
                "Perform manual regression verification because repository test coverage is limited.",
                "Run agentforge diff after implementation.");
    }

    @Test
    void derivesControllerAndPersistenceActionsOnlyFromScopeFacts() {
        RelevantFile controller = file("AlertController", FileResponsibility.CONTROLLER);
        RelevantFile mapper = file("AlertMapper", FileResponsibility.OTHER);
        Map<String, JavaRole> roles = new LinkedHashMap<>();
        roles.put(controller.path(), JavaRole.CONTROLLER);
        roles.put(mapper.path(), JavaRole.MAPPER);
        RepositoryReport repository = repository(List.of(controller, mapper), roles, 10, 5);

        List<String> actions = descriptions(advisor.advise(repository,
                new PlanScope(List.of(controller), List.of(mapper), List.of()), List.of(),
                TaskType.FEATURE, true, 0.10));

        assertThat(actions).containsSubsequence(
                "Verify affected API request/response behavior.",
                "Verify persistence reads/writes and transaction behavior.");
    }

    @Test
    void integrationTaskTriggersContractVerificationWithoutRelatedFlow() {
        RelevantFile service = file("CloudService", FileResponsibility.SERVICE);
        List<String> actions = descriptions(advisor.advise(repository(List.of(service), Map.of(), 10, 5),
                new PlanScope(List.of(service), List.of(), List.of()), List.of(),
                TaskType.INTEGRATION, true, 0.10));

        assertThat(actions).contains("Verify upstream and downstream integration contracts.");
    }

    @Test
    void analysisDoesNotReceiveBugfixRegressionGuidance() {
        RelevantFile service = file("AlertService", FileResponsibility.SERVICE);
        List<String> actions = descriptions(advisor.advise(repository(List.of(service), Map.of(), 10, 5),
                new PlanScope(List.of(service), List.of(), List.of()), List.of(),
                TaskType.ANALYSIS, true, 0.10));

        assertThat(actions).noneMatch(action -> action.contains("regression test"));
    }

    private RepositoryReport repository(List<RelevantFile> files, Map<String, JavaRole> roles,
                                        int sourceFiles, int testFiles) {
        Path root = Path.of("/tmp/test-advisor");
        Map<String, JavaTypeInfo> nodes = new LinkedHashMap<>();
        for (RelevantFile file : files) {
            String name = file.className().orElseThrow();
            JavaRole role = roles.getOrDefault(file.path(), JavaRole.SERVICE);
            nodes.put("demo." + name, new JavaTypeInfo("demo." + name, name, "demo", role, file.path()));
        }
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(nodes, List.of()), Map.of(),
                Map.of(), List.of(), List.of(), false, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        ContextReport context = new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
        return new RepositoryReport(new RepositoryInfo(root, true, root, "main", "head", false, List.of()),
                new ProjectTechnology(Set.of(BuildSystem.MAVEN), Set.of(BackendFramework.SPRING_BOOT), Set.of(), Set.of()),
                new RepositoryStructure(List.of("."), Map.of(), sourceFiles, testFiles, List.of()), context, architecture);
    }

    private RelevantFile file(String name, FileResponsibility responsibility) {
        return new RelevantFile("src/main/java/demo/" + name + ".java", Optional.of(name), ".", responsibility,
                30, new ConfidenceProfile(RelevanceLevel.MEDIUM, "advisor fixture"),
                List.of("告警"), List.of(), List.of());
    }

    private List<String> descriptions(List<PlanTest> tests) {
        return tests.stream().map(PlanTest::description).toList();
    }
}
