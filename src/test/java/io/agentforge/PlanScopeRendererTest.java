package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.TextReportRenderer;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ConfidenceProfile;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EstimatedChangeScope;
import io.agentforge.model.FileResponsibility;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.TaskType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanScopeRendererTest {
    @Test
    void rendersScopeSemanticsWithoutRepeatingInspectEvidence() {
        RelevantFile primary = file("A", RelevanceLevel.HIGH);
        RelevantFile secondary = file("B", RelevanceLevel.MEDIUM);
        RelevantFile reference = file("C", RelevanceLevel.LOW);
        EngineeringPlan plan = plan(new PlanScope(
                List.of(primary), List.of(secondary), List.of(reference)));

        String output = new TextReportRenderer().plan(plan);

        assertThat(output).contains(
                "PRIMARY — likely change targets\n\n1. A.java\n   src/main/java/demo/A.java",
                "SECONDARY — inspect before changing\n\n1. B.java\n   src/main/java/demo/B.java",
                "REFERENCE — read-only context\n\n1. C.java\n   src/main/java/demo/C.java");
        assertThat(output.indexOf("A.java")).isLessThan(output.indexOf("B.java"));
        assertThat(output.indexOf("B.java")).isLessThan(output.indexOf("C.java"));
        assertThat(output).doesNotContain(
                "Confidence Reason", "Core token matched", "Weak lexical recall signal");
    }

    private EngineeringPlan plan(PlanScope scope) {
        Path root = Path.of("/tmp/plan-renderer");
        RepositoryInfo repository = new RepositoryInfo(root, true, root, "main", "0123456789abcdef", false, List.of());
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(),
                Map.of(), List.of(), List.of(), false, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        ContextReport context = new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
        RepositoryReport report = new RepositoryReport(repository, new ProjectTechnology(Set.of(), Set.of(), Set.of(), Set.of()),
                new RepositoryStructure(List.of("."), Map.of(), 3, 1, List.of()), context, architecture);
        return new EngineeringPlan("分析任务", TaskType.ANALYSIS, "分析", PlanConfidence.MEDIUM, report,
                new GitBaseline(true, "main", "0123456789abcdef", GitBaseline.WorkingTreeState.CLEAN, 0),
                scope, PlanScopeOverlap.none(), List.of(), List.of(),
                new EstimatedChangeScope(1, 2, 1, false, false, false, false),
                new PlanRisk(5, PlanRisk.Level.LOW,
                        List.of(new PlanRisk.Factor(5, "Architecture contract is not configured"))),
                List.of(), List.of(), true, true, List.of());
    }

    private RelevantFile file(String name, RelevanceLevel level) {
        return new RelevantFile("src/main/java/demo/" + name + ".java", Optional.of(name), ".",
                FileResponsibility.SERVICE, 20, new ConfidenceProfile(level, "renderer fixture"),
                List.of("任务"), List.of(), List.of());
    }
}
