package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.ConfidenceProfile;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.FileResponsibility;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.model.MatchEvidence;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PlanConstraint;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RelevantModule;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.TaskAnalysis;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.model.TaskInspectionRequest;
import io.agentforge.planning.PlanEngine;
import io.agentforge.planning.PlanRiskEngine;
import io.agentforge.planning.PlanTestAdvisor;
import io.agentforge.planning.TaskTypeClassifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanEngineTest {
    private final PlanEngine engine = new PlanEngine(
            new TaskTypeClassifier(), new PlanRiskEngine(), new PlanTestAdvisor());

    @Test
    void createsStableFlowAwareScopeWithoutDiscoveringNewFiles(@TempDir Path root) {
        List<RelevantFile> files = List.of(
                file("A", RelevanceLevel.HIGH, ".", false),
                file("B", RelevanceLevel.MEDIUM, ".", false),
                file("C", RelevanceLevel.MEDIUM, ".", false),
                file("D", RelevanceLevel.LOW, ".", false),
                file("E", RelevanceLevel.LOW, ".", false));
        DependencyEdge flow = new DependencyEdge("demo.B", "demo.D", JavaRole.SERVICE, JavaRole.SERVICE);
        TaskInspectionReport inspection = report(root, files, List.of(flow), false, false, false, 5, 1);

        var plan = create(inspection, root, Optional.empty());

        assertThat(names(plan.scope().primary())).containsExactly("A", "B", "C");
        assertThat(names(plan.scope().secondary())).containsExactly("D");
        assertThat(names(plan.scope().reference())).containsExactly("E");
        assertThat(plan.confidence()).isEqualTo(PlanConfidence.MEDIUM);
        assertThat(plan.estimatedChangeScope().minimumFiles()).isEqualTo(3);
        assertThat(plan.estimatedChangeScope().maximumFiles()).isEqualTo(4);
        assertThat(allPaths(plan)).containsExactlyInAnyOrderElementsOf(files.stream().map(RelevantFile::path).toList());
        assertThat(plan.steps()).extracting(step -> step.description())
                .contains("Do not modify code unless explicitly requested.");
    }

    @Test
    void capsDisplayedPrimaryAndExplainsIndependentPreImplementationRisk(@TempDir Path root) {
        List<RelevantFile> files = List.of(
                relevant("Auth", "src/main/java/demo/security/Auth.java", RelevanceLevel.HIGH, "m1", false),
                file("B", RelevanceLevel.HIGH, "m1", false),
                file("C", RelevanceLevel.HIGH, "m2", false),
                file("D", RelevanceLevel.HIGH, "m2", false),
                file("E", RelevanceLevel.HIGH, "m3", false),
                file("F", RelevanceLevel.HIGH, "m3", false));
        TaskInspectionReport inspection = report(root, files, List.of(), true, true, false, 6, 1);
        List<ChangedFile> changed = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new ChangedFile(ChangeStatus.MODIFIED, "unrelated/" + index + ".txt"))
                .toList();
        ChangeReport changes = new ChangeReport(inspection.repositoryReport().repository(), changed, Set.of("."),
                false, false, false, false, false, false, List.of());

        var plan = create(inspection, root, Optional.empty(), Optional.of(changes));

        assertThat(names(plan.scope().primary())).containsExactly("Auth", "B", "C", "D", "E");
        assertThat(names(plan.scope().secondary())).containsExactly("F");
        assertThat(plan.preImplementationRisk().level()).isEqualTo(PlanRisk.Level.HIGH);
        assertThat(plan.preImplementationRisk().score()).isEqualTo(75);
        assertThat(plan.preImplementationRisk().factors()).extracting(PlanRisk.Factor::reason).containsExactly(
                "Protected policy path appears in the inspect results",
                "Security path appears in the inspect results",
                "More than 5 files qualify as PRIMARY candidates",
                "Inspect results span more than 2 modules",
                "Working tree contains 6 existing changed files",
                "Architecture contract is not configured");
    }

    @Test
    void countsOneLogicalRenameOnceWhenOldAndNewPathsBothOverlapScope(@TempDir Path root) {
        List<RelevantFile> files = List.of(
                file("A", RelevanceLevel.HIGH, ".", false),
                file("B", RelevanceLevel.MEDIUM, ".", false),
                file("C", RelevanceLevel.MEDIUM, ".", false));
        TaskInspectionReport inspection = report(root, files, List.of(), true, true, true, 3, 1);
        ChangedFile rename = new ChangedFile(ChangeStatus.RENAMED,
                files.get(1).path(), files.get(0).path());
        ChangeReport changes = new ChangeReport(inspection.repositoryReport().repository(), List.of(rename), Set.of("."),
                false, false, false, false, false, false, List.of());

        var plan = create(inspection, root, Optional.empty(), Optional.of(changes));

        assertThat(names(plan.scope().primary())).containsExactly("A", "B", "C");
        assertThat(plan.scopeOverlap().changedRelevantFiles()).isEqualTo(1);
        assertThat(plan.scopeOverlap().overlappingPaths()).containsExactly(files.get(0).path(), files.get(1).path());
        assertThat(plan.scopeOverlap().primaryOverlap()).isTrue();
        assertThat(plan.preImplementationRisk().factors()).extracting(PlanRisk.Factor::reason)
                .contains("Existing changes overlap PRIMARY plan scope");
    }

    @Test
    void usesOnlyExplicitStructuralEvidenceForHighPlanConfidence(@TempDir Path root) {
        List<RelevantFile> files = List.of(
                file("A", RelevanceLevel.MEDIUM, ".", true),
                file("B", RelevanceLevel.MEDIUM, ".", true),
                file("C", RelevanceLevel.MEDIUM, ".", true));
        TaskInspectionReport inspection = report(root, files, List.of(), false, false, false, 3, 1);

        var plan = create(inspection, root, Optional.empty());

        assertThat(plan.confidence()).isEqualTo(PlanConfidence.HIGH);
    }

    @Test
    void keepsLowOnlyEvidenceAsReferenceInsteadOfGuessingAnImplementationScope(@TempDir Path root) {
        List<RelevantFile> files = List.of(
                file("D", RelevanceLevel.LOW, ".", false),
                file("E", RelevanceLevel.LOW, ".", false));
        DependencyEdge flow = new DependencyEdge("demo.D", "demo.E", JavaRole.SERVICE, JavaRole.SERVICE);
        TaskInspectionReport inspection = report(root, files, List.of(flow), false, false, false, 2, 0);

        var plan = create(inspection, root, Optional.empty());

        assertThat(plan.reliableScope()).isFalse();
        assertThat(plan.scope().primary()).isEmpty();
        assertThat(plan.scope().secondary()).isEmpty();
        assertThat(names(plan.scope().reference())).containsExactly("D", "E");
        assertThat(plan.estimatedChangeScope().maximumFiles()).isZero();
        assertThat(plan.confidence()).isEqualTo(PlanConfidence.LOW);
    }

    @Test
    void preservesDeclaredArchitectureContractOrderAndPolicyConstraints(@TempDir Path root) {
        LinkedHashMap<String, ArchitectureContract.LayerRule> rules = new LinkedHashMap<>();
        rules.put("controller", new ArchitectureContract.LayerRule(List.of("service")));
        rules.put("service", new ArchitectureContract.LayerRule(List.of("mapper")));
        rules.put("mapper", new ArchitectureContract.LayerRule(List.of()));
        ArchitectureContract contract = new ArchitectureContract(1,
                new ArchitectureContract.Architecture(List.of("controller", "service", "mapper", "external"), rules));
        TaskInspectionReport inspection = report(root,
                List.of(file("A", RelevanceLevel.MEDIUM, ".", false),
                        file("B", RelevanceLevel.MEDIUM, ".", false)),
                List.of(), false, false, true, 2, 1);

        var plan = create(inspection, root, Optional.of(contract));

        assertThat(plan.constraints().stream()
                .filter(item -> item.kind() == PlanConstraint.Kind.ARCHITECTURE_LAYER)
                .map(PlanConstraint::description)).containsExactly("controller", "service", "mapper", "external");
        assertThat(plan.constraints().stream()
                .filter(item -> item.kind() == PlanConstraint.Kind.ARCHITECTURE_ALLOWED
                        || item.kind() == PlanConstraint.Kind.ARCHITECTURE_UNDECLARED)
                .map(PlanConstraint::description)).containsExactly(
                        "controller -> service", "service -> mapper", "mapper -> NONE", "external -> NOT DECLARED");
        assertThat(plan.constraints()).anyMatch(item -> item.kind() == PlanConstraint.Kind.POLICY_DENY
                && item.description().equals("pom.xml"));
        assertThat(plan.constraints()).anyMatch(item -> item.kind() == PlanConstraint.Kind.POLICY_PROTECTED
                && item.description().equals("src/main/java/**/security/**"));
    }

    private io.agentforge.model.EngineeringPlan create(TaskInspectionReport inspection, Path root,
                                                       Optional<ArchitectureContract> contract) {
        return create(inspection, root, contract, Optional.empty());
    }

    private io.agentforge.model.EngineeringPlan create(TaskInspectionReport inspection, Path root,
                                                       Optional<ArchitectureContract> contract,
                                                       Optional<ChangeReport> changes) {
        var policy = new ConfigLoader().loadPolicy(root);
        return engine.create(inspection, policy.config(), policy.defaultUsed(), contract, changes);
    }

    private TaskInspectionReport report(Path root, List<RelevantFile> files, List<DependencyEdge> flow,
                                        boolean git, boolean dirty, boolean contractConfigured,
                                        int sourceFiles, int testFiles) {
        Map<String, JavaTypeInfo> nodes = new LinkedHashMap<>();
        files.forEach(file -> {
            String name = file.className().orElseThrow();
            nodes.put("demo." + name, new JavaTypeInfo("demo." + name, name, "demo",
                    JavaRole.SERVICE, file.path()));
        });
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(nodes, flow),
                Map.of(JavaRole.SERVICE, files.size()), Map.of(), List.of(), List.of(), contractConfigured,
                ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        RepositoryInfo repository = new RepositoryInfo(root, git, git ? root : null, git ? "main" : "N/A",
                git ? "1234567890abcdef" : "N/A", dirty, List.of());
        List<String> modules = files.stream().map(RelevantFile::module).distinct().toList();
        RepositoryStructure structure = new RepositoryStructure(modules, Map.of(), sourceFiles, testFiles, List.of());
        ContextReport context = new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
        RepositoryReport repositoryReport = new RepositoryReport(repository,
                new ProjectTechnology(Set.of(BuildSystem.MAVEN), Set.of(BackendFramework.SPRING_BOOT),
                        Set.of(), Set.of()), structure, context, architecture);
        List<RelevantModule> relevantModules = new ArrayList<>();
        modules.forEach(module -> relevantModules.add(new RelevantModule(module, 20,
                files.stream().filter(file -> file.module().equals(module)).map(RelevantFile::path).toList())));
        return new TaskInspectionReport(new TaskInspectionRequest(root, "分析历史告警流程", 15), repositoryReport,
                new TaskAnalysis(List.of("历史", "告警"), List.of("历史告警"), List.of()), files,
                relevantModules, List.of(), flow, List.of());
    }

    private RelevantFile file(String name, RelevanceLevel level, String module, boolean structural) {
        return relevant(name, "src/main/java/demo/" + name + ".java", level, module, structural);
    }

    private RelevantFile relevant(String name, String path, RelevanceLevel level, String module, boolean structural) {
        List<MatchEvidence> evidence = structural
                ? List.of(new MatchEvidence("CLASS_OR_FILE", 30, "Class matched task"))
                : List.of(new MatchEvidence("CORE_TOKEN", 10, "Task keyword matched"));
        return new RelevantFile(path, Optional.of(name), module, FileResponsibility.SERVICE, 30,
                new ConfidenceProfile(level, "test confidence"), List.of("历史", "告警"), List.of(), evidence);
    }

    private List<String> names(List<RelevantFile> files) {
        return files.stream().map(file -> file.className().orElseThrow()).toList();
    }

    private List<String> allPaths(io.agentforge.model.EngineeringPlan plan) {
        List<String> paths = new ArrayList<>();
        plan.scope().primary().forEach(file -> paths.add(file.path()));
        plan.scope().secondary().forEach(file -> paths.add(file.path()));
        plan.scope().reference().forEach(file -> paths.add(file.path()));
        return paths;
    }
}
