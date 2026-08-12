package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.PolicyViolation;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RiskLevel;
import io.agentforge.model.RiskReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationStatus;
import io.agentforge.model.ViolationSeverity;
import io.agentforge.verification.CheckEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckEngineTest {
    private final CheckEngine engine = new CheckEngine();

    @Test
    void passesWithNoChangesOrOnlyPrimaryChanges() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);

        assertThat(engine.verify(plan, diff(plan, List.of(), new PolicyReport(List.of()), architecture(false)))
                .overallStatus()).isEqualTo(VerificationStatus.PASS);
        assertThat(engine.verify(plan, diff(plan,
                List.of(changed("src/main/java/demo/PrimaryService.java")),
                new PolicyReport(List.of()), architecture(false))).overallStatus())
                .isEqualTo(VerificationStatus.PASS);
    }

    @Test
    void secondaryWarnsWhileReferenceAndOutOfScopeFail() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);

        var secondary = engine.verify(plan, diff(plan,
                List.of(changed("src/main/java/demo/SecondaryService.java")),
                new PolicyReport(List.of()), architecture(false)));
        var reference = engine.verify(plan, diff(plan,
                List.of(changed("src/main/java/demo/TaskRequest.java")),
                new PolicyReport(List.of()), architecture(false)));
        var outside = engine.verify(plan, diff(plan, List.of(changed("README.md")),
                new PolicyReport(List.of()), architecture(false)));

        assertThat(secondary.overallStatus()).isEqualTo(VerificationStatus.WARNING);
        assertThat(secondary.findings()).extracting(VerificationFinding::code)
                .containsExactly("SECONDARY_SCOPE_MODIFIED");
        assertThat(reference.overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(reference.findings()).extracting(VerificationFinding::code)
                .containsExactly("REFERENCE_SCOPE_MODIFIED");
        assertThat(outside.overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(outside.findings()).extracting(VerificationFinding::code)
                .containsExactly("OUT_OF_SCOPE_MODIFICATION");
    }

    @Test
    void verifiesRenamePathsIndependentlyAndUsesStrictestStatus() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        ChangedFile rename = new ChangedFile(ChangeStatus.RENAMED,
                "src/main/java/demo/RenamedService.java", "src/main/java/demo/SecondaryService.java");

        var report = engine.verify(plan, diff(plan, List.of(rename),
                new PolicyReport(List.of()), architecture(false)));

        assertThat(report.scopeStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(report.findings()).filteredOn(finding -> finding.category() == VerificationFinding.Category.SCOPE)
                .extracting(VerificationFinding::code, VerificationFinding::path)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("OUT_OF_SCOPE_MODIFICATION",
                                "src/main/java/demo/RenamedService.java"),
                        org.assertj.core.groups.Tuple.tuple("SECONDARY_SCOPE_MODIFIED",
                                "src/main/java/demo/SecondaryService.java"));
    }

    @Test
    void analysisSourceAndScopeFindingsDoNotSuppressEachOther() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.ANALYSIS);

        var report = engine.verify(plan, diff(plan,
                List.of(changed("src/main/java/demo/OutsideService.java"), changed("README.md")),
                new PolicyReport(List.of()), architecture(false)));

        assertThat(report.overallStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(report.taskTypeStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(report.findings()).extracting(VerificationFinding::code)
                .contains("OUT_OF_SCOPE_MODIFICATION", "ANALYSIS_SOURCE_MODIFICATION");
        assertThat(report.findings()).filteredOn(finding -> finding.code().equals("ANALYSIS_SOURCE_MODIFICATION"))
                .extracting(VerificationFinding::path)
                .containsExactly("src/main/java/demo/OutsideService.java");
    }

    @Test
    void preservesEveryUnderlyingPolicyViolation() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.BUGFIX);
        PolicyReport policy = new PolicyReport(List.of(
                new PolicyViolation(ViolationSeverity.ERROR, "change.max_files", "-", "Too many files"),
                new PolicyViolation(ViolationSeverity.ERROR, "change.deny", "pom.xml", "Denied file changed"),
                new PolicyViolation(ViolationSeverity.WARN, "architecture.dependencies", "Demo.java", "Layer violation")));

        var report = engine.verify(plan, diff(plan, List.of(), policy, architecture(false)));

        assertThat(report.policyStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(report.findings()).filteredOn(finding -> finding.category() == VerificationFinding.Category.POLICY)
                .hasSize(3)
                .extracting(VerificationFinding::message)
                .containsExactly(
                        "change.max_files — Too many files",
                        "architecture.dependencies — Layer violation",
                        "change.deny — Denied file changed");
    }

    @Test
    void architectureNotConfiguredIsNonFailingButConfiguredViolationFailsFromExistingFacts() {
        EngineeringPlan withoutContract = PromptTestFixtures.plan(TaskType.BUGFIX);
        var notConfigured = engine.verify(withoutContract, diff(withoutContract, List.of(),
                new PolicyReport(List.of()), architecture(false)));

        EngineeringPlan configured = PromptTestFixtures.plan(TaskType.BUGFIX,
                withoutContract.gitBaseline(), withoutContract.scopeOverlap(), true, false,
                withoutContract.preImplementationRisk());
        DependencyEdge violation = new DependencyEdge("demo.Controller", "demo.Mapper",
                JavaRole.CONTROLLER, JavaRole.MAPPER);
        ArchitectureReport architecture = architecture(true, List.of(violation));
        var failed = engine.verify(configured, diff(configured,
                List.of(changed("src/main/java/demo/Controller.java")),
                new PolicyReport(List.of()), architecture));

        assertThat(notConfigured.architectureStatus()).isEqualTo(VerificationStatus.PASS);
        assertThat(notConfigured.overallStatus()).isEqualTo(VerificationStatus.PASS);
        assertThat(failed.architectureStatus()).isEqualTo(VerificationStatus.FAIL);
        assertThat(failed.findings()).extracting(VerificationFinding::code)
                .contains("ARCHITECTURE_CONSTRAINT_VIOLATION");
    }

    @Test
    void sameFactsAlwaysProduceSameOrderedReport() {
        EngineeringPlan plan = PromptTestFixtures.plan(TaskType.ANALYSIS);
        DiffAuditReport diff = diff(plan, List.of(
                changed("z/Outside.java"), changed("a/Outside.java"),
                changed("src/main/java/demo/SecondaryService.java")),
                new PolicyReport(List.of()), architecture(false));

        var first = engine.verify(plan, diff);
        var second = engine.verify(plan, diff);
        var third = engine.verify(plan, diff);

        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(first.findings()).extracting(VerificationFinding::path)
                .containsExactly("a/Outside.java", "src/main/java/demo/SecondaryService.java", "z/Outside.java",
                        "a/Outside.java", "src/main/java/demo/SecondaryService.java", "z/Outside.java");
    }

    private DiffAuditReport diff(EngineeringPlan plan, List<ChangedFile> files,
                                 PolicyReport policy, ArchitectureReport architecture) {
        RepositoryInfo source = plan.repository().repository();
        RepositoryInfo repository = new RepositoryInfo(source.path(), true, source.path(), source.branch(),
                source.head(), !files.isEmpty(), List.of());
        ChangeReport change = new ChangeReport(repository, files, files.isEmpty() ? Set.of() : Set.of("."),
                false, false, false, false, false, false, List.of());
        return new DiffAuditReport(change, policy, new ScoreBreakdown("Minimal Change", 100, List.of()),
                new RiskReport(0, RiskLevel.LOW, List.of()), architecture, false);
    }

    private ArchitectureReport architecture(boolean configured) {
        return architecture(configured, List.of());
    }

    private ArchitectureReport architecture(boolean configured, List<DependencyEdge> violations) {
        Map<String, JavaTypeInfo> nodes = violations.isEmpty() ? Map.of() : Map.of(
                "demo.Controller", new JavaTypeInfo("demo.Controller", "Controller", "demo",
                        JavaRole.CONTROLLER, "src/main/java/demo/Controller.java"),
                "demo.Mapper", new JavaTypeInfo("demo.Mapper", "Mapper", "demo",
                        JavaRole.MAPPER, "src/main/java/demo/Mapper.java"));
        return new ArchitectureReport(new DependencyGraph(nodes, violations), Map.of(), Map.of(),
                List.of(), violations, configured, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
    }

    private ChangedFile changed(String path) {
        return new ChangedFile(ChangeStatus.MODIFIED, path);
    }
}
