package io.agentforge;

import io.agentforge.model.AcceptanceCriterion;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.ConfidenceProfile;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EstimatedChangeScope;
import io.agentforge.model.FileResponsibility;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PlanConstraint;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.PlanStep;
import io.agentforge.model.PlanTest;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.TaskType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class PromptTestFixtures {
    private PromptTestFixtures() {}

    static EngineeringPlan plan(TaskType type) {
        return plan(type,
                new GitBaseline(true, "main", "0123456789abcdef", GitBaseline.WorkingTreeState.CLEAN, 0),
                PlanScopeOverlap.none(), false, true,
                new PlanRisk(5, PlanRisk.Level.LOW,
                        List.of(new PlanRisk.Factor(5, "Architecture contract is not configured"))));
    }

    static EngineeringPlan plan(TaskType type, GitBaseline baseline, PlanScopeOverlap overlap,
                                boolean architectureConfigured, boolean defaultPolicy,
                                PlanRisk risk) {
        RelevantFile primary = file("PrimaryService", RelevanceLevel.HIGH);
        RelevantFile secondary = file("SecondaryService", RelevanceLevel.MEDIUM);
        RelevantFile reference = file("TaskRequest", RelevanceLevel.LOW);
        PlanScope scope = new PlanScope(List.of(primary), List.of(secondary), List.of(reference));
        return plan(type, baseline, overlap, architectureConfigured, defaultPolicy, risk, scope, true);
    }

    static EngineeringPlan plan(TaskType type, GitBaseline baseline, PlanScopeOverlap overlap,
                                boolean architectureConfigured, boolean defaultPolicy,
                                PlanRisk risk, PlanScope scope, boolean reliableScope) {
        Path root = Path.of("/tmp/prompt-fixture");
        RepositoryInfo repository = new RepositoryInfo(root, baseline.available(), baseline.available() ? root : null,
                baseline.branch(), baseline.head(),
                baseline.workingTree() == GitBaseline.WorkingTreeState.DIRTY, List.of());
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(),
                Map.of(), List.of(), List.of(), architectureConfigured, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        ContextReport context = new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
        RepositoryReport report = new RepositoryReport(repository,
                new ProjectTechnology(Set.of(BuildSystem.MAVEN), Set.of(BackendFramework.SPRING_BOOT),
                        Set.of(), Set.of()),
                new RepositoryStructure(List.of("."), Map.of(), 20, 2, List.of()), context, architecture);
        List<PlanConstraint> constraints = new ArrayList<>();
        constraints.add(new PlanConstraint(PlanConstraint.Kind.POLICY_DENY, "pom.xml"));
        constraints.add(new PlanConstraint(PlanConstraint.Kind.POLICY_PROTECTED, "src/main/java/**/security/**"));
        if (architectureConfigured) {
            constraints.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_LAYER, "controller"));
            constraints.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_LAYER, "service"));
            constraints.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_ALLOWED, "controller -> service"));
            constraints.add(new PlanConstraint(PlanConstraint.Kind.ARCHITECTURE_ALLOWED, "service -> NONE"));
        } else {
            constraints.add(new PlanConstraint(PlanConstraint.Kind.OBSERVED_FLOW,
                    "demo.PrimaryService -> demo.SecondaryService"));
        }
        return new EngineeringPlan(task(type), type, signal(type),
                reliableScope ? PlanConfidence.MEDIUM : PlanConfidence.LOW, report, baseline,
                scope, overlap, steps(type), constraints,
                reliableScope ? new EstimatedChangeScope(1, 2, 1, false, false, false, false)
                        : new EstimatedChangeScope(0, 0, 0, false, false, false, false),
                risk, tests(type), acceptance(), defaultPolicy, reliableScope,
                defaultPolicy ? List.of("Built-in policy defaults are being used.") : List.of());
    }

    static RelevantFile file(String name, RelevanceLevel level) {
        return new RelevantFile("src/main/java/demo/" + name + ".java", Optional.of(name), ".",
                FileResponsibility.SERVICE, 20, new ConfidenceProfile(level, "fixture evidence"),
                List.of("task"), List.of(), List.of());
    }

    private static List<PlanStep> steps(TaskType type) {
        List<String> descriptions = new ArrayList<>(List.of(
                "Start from PRIMARY files.",
                "Do not modify SECONDARY files unless PRIMARY analysis demonstrates that the change is required.",
                "Treat REFERENCE files as read-only context unless direct evidence requires changing them."));
        descriptions.addAll(switch (type) {
            case BUGFIX -> List.of("Reproduce or trace the current failure.", "Identify the root cause.",
                    "Modify the minimum necessary code.");
            case FEATURE -> List.of("Confirm the current extension point.", "Implement the minimum feature path.");
            case INTEGRATION -> List.of("Inspect the existing integration path.", "Reuse the existing contract.",
                    "Verify upstream and downstream behavior.");
            case REFACTOR -> List.of("Confirm the behavior baseline.", "Preserve external behavior.");
            case ANALYSIS -> List.of("Inspect the relevant files.", "Trace the known dependency flow.",
                    "Document the findings.", "Do not modify code unless explicitly requested.");
            case UNKNOWN -> List.of("Confirm the current task flow.", "Prefer minimal changes.");
        });
        descriptions.add("Execute the generated Test Plan.");
        descriptions.add("Run agentforge diff after implementation.");
        List<PlanStep> result = new ArrayList<>();
        for (int index = 0; index < descriptions.size(); index++) {
            result.add(new PlanStep(index + 1, descriptions.get(index)));
        }
        return result;
    }

    private static List<PlanTest> tests(TaskType type) {
        List<PlanTest> result = new ArrayList<>();
        result.add(new PlanTest("Run Maven tests."));
        if (type == TaskType.BUGFIX) result.add(new PlanTest("Add or run a regression test covering the reproduced failure path."));
        result.add(new PlanTest("Verify upstream and downstream integration contracts."));
        result.add(new PlanTest("Run agentforge diff after implementation."));
        return result;
    }

    private static List<AcceptanceCriterion> acceptance() {
        return List.of(
                new AcceptanceCriterion("Relevant task behavior is implemented or verified."),
                new AcceptanceCriterion("No unrelated modules are modified."),
                new AcceptanceCriterion("Tests pass."));
    }

    private static String task(TaskType type) {
        return switch (type) {
            case BUGFIX -> "修复任务异常";
            case FEATURE -> "增加任务功能";
            case INTEGRATION -> "接入任务同步";
            case REFACTOR -> "重构任务服务";
            case ANALYSIS -> "分析任务流程";
            case UNKNOWN -> "整理任务说明";
        };
    }

    private static String signal(TaskType type) {
        return type == TaskType.UNKNOWN ? "No deterministic task-type keyword matched" : type.name().toLowerCase();
    }
}
