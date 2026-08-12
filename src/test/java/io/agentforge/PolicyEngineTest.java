package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.policy.PolicyEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {
    private final PolicyConfig defaults = new ConfigLoader().loadPolicy(Path.of("missing-test-root")).config();
    private final PolicyEngine engine = new PolicyEngine();

    @Test
    void rejectsChangesAboveConfiguredFileLimit() {
        List<ChangedFile> files = IntStream.rangeClosed(0, defaults.change().maxFiles())
                .mapToObj(index -> file("src/File" + index + ".java"))
                .toList();

        assertThat(evaluate(files, defaults).violations())
                .anyMatch(violation -> violation.rule().equals("change.max_files"));
    }

    @Test
    void basenameDenyMatchesRootAndNestedDependencyDescriptors() {
        PolicyReport report = evaluate(List.of(
                file("pom.xml"), file("backend/pom.xml"), file("modules/user/pom.xml"),
                file("package.json"), file("frontend/package.json")), defaults);

        assertThat(report.violations().stream().filter(v -> v.rule().equals("change.deny")))
                .extracting(v -> v.path())
                .containsExactlyInAnyOrder("pom.xml", "backend/pom.xml", "modules/user/pom.xml",
                        "package.json", "frontend/package.json");
    }

    @Test
    void doubleStarProtectedGlobMatchesNestedMigrations() {
        PolicyReport report = evaluate(List.of(
                file("src/main/resources/db/migration/V1.sql"),
                file("backend/src/main/resources/db/migration/V2.sql")), defaults);

        assertThat(report.violations().stream().filter(v -> v.rule().equals("change.protected")))
                .extracting(v -> v.path())
                .contains("src/main/resources/db/migration/V1.sql", "backend/src/main/resources/db/migration/V2.sql");
    }

    @Test
    void singleStarDoesNotCrossDirectoryBoundary() {
        PolicyConfig config = withChange(new PolicyConfig.ChangePolicy(20, List.of(), List.of("config/*.yaml")));

        PolicyReport report = evaluate(List.of(file("config/app.yaml"), file("config/nested/app.yaml")), config);

        assertThat(report.violations()).extracting(v -> v.path()).containsExactly("config/app.yaml");
    }

    @Test
    void renameEvaluatesBothOldAndNewPaths() {
        ChangedFile movedOutOfSecurity = new ChangedFile(ChangeStatus.RENAMED,
                "common/Auth.java", "src/main/java/demo/security/Auth.java");
        ChangedFile renamedToDenied = new ChangedFile(ChangeStatus.RENAMED,
                "modules/user/pom.xml", "modules/user/build.xml");

        PolicyReport report = evaluate(List.of(movedOutOfSecurity, renamedToDenied), defaults);

        assertThat(report.violations()).extracting(v -> v.path())
                .contains("src/main/java/demo/security/Auth.java", "modules/user/pom.xml");
    }

    @Test
    void renameCountsOnceForMaxFilesWhileBothPathsRemainPolicyInputs() {
        PolicyConfig config = withChange(new PolicyConfig.ChangePolicy(1,
                List.of("old/Denied.java"), List.of("new/Protected.java")));
        ChangedFile rename = new ChangedFile(ChangeStatus.RENAMED,
                "new/Protected.java", "old/Denied.java");

        PolicyReport report = engine.evaluate(List.of(rename), config, emptyArchitecture());

        assertThat(report.violations()).extracting(v -> v.rule(), v -> v.path())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("change.deny", "old/Denied.java"),
                        org.assertj.core.groups.Tuple.tuple("change.protected", "new/Protected.java"));
        assertThat(report.violations()).noneMatch(v -> v.rule().equals("change.max_files"));
    }

    private PolicyReport evaluate(List<ChangedFile> files, PolicyConfig config) {
        return engine.evaluate(change(files), config, emptyArchitecture());
    }

    private PolicyConfig withChange(PolicyConfig.ChangePolicy change) {
        return new PolicyConfig(defaults.schemaVersion(), change, defaults.minimalChange(), defaults.risk(), defaults.readiness());
    }

    private ChangeReport change(List<ChangedFile> files) {
        return new ChangeReport(new RepositoryInfo(Path.of("."), true, Path.of("."), "main", "abc", true, List.of()),
                files, Set.of("."), false, false, false, false, false, false, List.of());
    }

    private ArchitectureReport emptyArchitecture() {
        return new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(), Map.of(),
                List.of(), List.of(), false, io.agentforge.model.ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
    }

    private ChangedFile file(String path) {
        return new ChangedFile(ChangeStatus.MODIFIED, path);
    }
}
