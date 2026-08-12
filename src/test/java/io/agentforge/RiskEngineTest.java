package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.PolicyViolation;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RiskLevel;
import io.agentforge.model.ViolationSeverity;
import io.agentforge.risk.RiskEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskEngineTest {
    private final PolicyConfig defaults = new ConfigLoader().loadPolicy(Path.of("missing-test-root")).config();
    private final RiskEngine engine = new RiskEngine();
    private final PolicyReport passed = new PolicyReport(List.of());

    @Test
    void policyViolationIsOneFactor() {
        PolicyReport failed = new PolicyReport(List.of(
                new PolicyViolation(ViolationSeverity.ERROR, "change.deny", "pom.xml", "denied"),
                new PolicyViolation(ViolationSeverity.ERROR, "change.protected", "security/A.java", "protected")));

        assertSingleFactor(change(List.of(), Set.of(), false, false, false, false, false, false),
                failed, defaults.risk().policyViolation(), "Policy violation detected");
    }

    @Test
    void dependencyChangeIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of(), false, true, false, false, false, false), passed,
                defaults.risk().dependencyChange(), "Dependency descriptor changed");
    }

    @Test
    void securityChangeIsCountedOnceForManyFiles() {
        List<ChangedFile> files = IntStream.range(0, 10)
                .mapToObj(i -> file("src/main/java/demo/security/S" + i + ".java")).toList();
        assertSingleFactor(change(files, Set.of("."), false, false, false, true, false, false), passed,
                defaults.risk().securityChange(), "Security files changed");
    }

    @Test
    void databaseChangeIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of(), false, false, true, false, false, false), passed,
                defaults.risk().databaseChange(), "Database migration or SQL changed");
    }

    @Test
    void filesOverLimitIsOneFactor() {
        List<ChangedFile> files = IntStream.rangeClosed(0, defaults.change().maxFiles())
                .mapToObj(i -> file("src/File" + i + ".java")).toList();
        assertSingleFactor(change(files, Set.of("."), false, false, false, false, false, false), passed,
                defaults.risk().filesOverLimit(), "Changed files exceed policy maximum");
    }

    @Test
    void modulesOverLimitIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of("a", "b", "c", "d"), false, false, false, false, false, false), passed,
                defaults.risk().modulesOverLimit(), "More than 3 modules changed");
    }

    @Test
    void controllerChangeIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of(), false, false, false, false, false, true), passed,
                defaults.risk().controllerChange(), "API controller changed");
    }

    @Test
    void agentsChangeIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of(), false, false, false, false, true, false), passed,
                defaults.risk().agentsChange(), "Agent governance context changed");
    }

    @Test
    void configChangeIsOneFactor() {
        assertSingleFactor(change(List.of(), Set.of(), true, false, false, false, false, false), passed,
                defaults.risk().configChange(), "Configuration changed");
    }

    @ParameterizedTest
    @CsvSource({
            "19, LOW",
            "20, MEDIUM",
            "39, MEDIUM",
            "40, HIGH",
            "69, HIGH",
            "70, CRITICAL"
    })
    void mapsExactRiskThresholdBoundaries(int points, RiskLevel expected) {
        PolicyConfig config = withDependencyRisk(points);

        var report = engine.evaluate(change(List.of(), Set.of(), false, true, false, false, false, false), passed, config);

        assertThat(report.score()).isEqualTo(points);
        assertThat(report.level()).isEqualTo(expected);
    }

    private void assertSingleFactor(ChangeReport change, PolicyReport policyReport, int points, String reason) {
        var report = engine.evaluate(change, policyReport, defaults);

        assertThat(report.score()).isEqualTo(points);
        assertThat(report.factors()).singleElement().satisfies(factor -> {
            assertThat(factor.points()).isEqualTo(points);
            assertThat(factor.reason()).isEqualTo(reason);
        });
    }

    private PolicyConfig withDependencyRisk(int points) {
        PolicyConfig.RiskPolicy base = defaults.risk();
        PolicyConfig.RiskPolicy risk = new PolicyConfig.RiskPolicy(
                20, 40, 70, base.maxModules(), base.policyViolation(), points,
                base.securityChange(), base.databaseChange(), base.filesOverLimit(), base.modulesOverLimit(),
                base.controllerChange(), base.agentsChange(), base.configChange());
        return new PolicyConfig(defaults.schemaVersion(), defaults.change(), defaults.minimalChange(), risk, defaults.readiness());
    }

    private ChangeReport change(List<ChangedFile> files, Set<String> modules, boolean config, boolean dependency,
                                boolean database, boolean security, boolean agents, boolean controller) {
        return new ChangeReport(new RepositoryInfo(Path.of("."), true, Path.of("."), "main", "abc", !files.isEmpty(), List.of()),
                files, modules, config, dependency, database, security, agents, controller, List.of());
    }

    private ChangedFile file(String path) {
        return new ChangedFile(ChangeStatus.MODIFIED, path);
    }
}
