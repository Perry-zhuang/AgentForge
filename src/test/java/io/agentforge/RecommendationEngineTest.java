package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DatabaseTechnology;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.FileType;
import io.agentforge.model.JavaRole;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.Recommendation;
import io.agentforge.model.RecommendationCategory;
import io.agentforge.model.RecommendationPriority;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.policy.ReadinessEngine;
import io.agentforge.recommendation.RecommendationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecommendationEngineTest {
    private final ReadinessEngine readiness = new ReadinessEngine();
    private final RecommendationEngine engine = new RecommendationEngine(readiness);

    @Test
    void generatesAllApplicableRecommendationsWithEvidenceAndStableOrder(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        PolicyConfig config = defaults();
        RepositoryReport repository = impLike(root, missingContext(), false, 1_312, 3);

        var report = evaluate(root, repository, config, false);

        assertThat(report.recommendations()).extracting(Recommendation::id).containsExactly(
                "context.agents.missing",
                "change-safety.policies.missing",
                "architecture.contract.missing",
                "context.database-rules.missing",
                "documentation.architecture.missing",
                "testability.file-ratio.low",
                "testability.rules.missing",
                "context.api-rules.missing",
                "context.skills.missing");
        assertThat(report.recommendations()).allSatisfy(recommendation -> {
            assertThat(recommendation.evidence()).isNotEmpty();
            assertThat(recommendation.reason()).isNotBlank();
            assertThat(recommendation.action()).isNotBlank();
        });
        assertThat(find(report.recommendations(), "context.database-rules.missing").priority())
                .isEqualTo(RecommendationPriority.HIGH);
    }

    @Test
    void computesDefaultImpactsByReevaluatingReadiness(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        PolicyConfig config = defaults();
        RepositoryReport repository = impLike(root, missingContext(), false, 1_312, 3);

        var recommendations = evaluate(root, repository, config, false).recommendations();

        assertThat(find(recommendations, "context.agents.missing").estimatedImpact()).isEqualTo(11);
        assertThat(find(recommendations, "change-safety.policies.missing").estimatedImpact()).isEqualTo(8);
        assertThat(find(recommendations, "architecture.contract.missing").estimatedImpact()).isEqualTo(7);
        assertThat(find(recommendations, "documentation.architecture.missing").estimatedImpact()).isEqualTo(15);
        assertThat(find(recommendations, "testability.file-ratio.low").estimatedImpact()).isEqualTo(5);
    }

    @Test
    void respectsCustomWeightsAndOverallRounding(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        PolicyConfig defaults = defaults();
        PolicyConfig.ContextWeights contextWeights = new PolicyConfig.ContextWeights(
                5, defaults.readiness().context().readme(), defaults.readiness().context().skills(),
                defaults.readiness().context().agentRules(), defaults.readiness().context().gitRules(),
                defaults.readiness().context().databaseRules(), defaults.readiness().context().apiRules(),
                defaults.readiness().context().testingRules(), defaults.readiness().context().agentsTooLargePenalty(),
                defaults.readiness().context().agentsMaxLines());
        PolicyConfig.DocumentationWeights documentation = new PolicyConfig.DocumentationWeights(
                defaults.readiness().documentation().readme(), defaults.readiness().documentation().architectureDoc(),
                5, defaults.readiness().documentation().docsDirectory());
        PolicyConfig.ReadinessPolicy readinessPolicy = new PolicyConfig.ReadinessPolicy(
                contextWeights, defaults.readiness().architecture(), defaults.readiness().changeSafety(),
                defaults.readiness().testability(), documentation);
        PolicyConfig custom = new PolicyConfig(defaults.schemaVersion(), defaults.change(), defaults.minimalChange(),
                defaults.risk(), readinessPolicy);
        RepositoryReport repository = impLike(root, missingContext(), false, 1_312, 3);

        Recommendation agents = find(evaluate(root, repository, custom, false).recommendations(), "context.agents.missing");

        assertThat(agents.estimatedImpact()).isEqualTo(2);
    }

    @Test
    void recommendsReducingOversizedAgentsWithoutUnrelatedRecommendations(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        ContextReport context = completeContext(301);
        RepositoryReport repository = impLike(root, context, true, 10, 1);

        var report = evaluate(root, repository, defaults(), true);

        assertThat(report.recommendations()).singleElement().satisfies(recommendation -> {
            assertThat(recommendation.id()).isEqualTo("context.agents.too-large");
            assertThat(recommendation.priority()).isEqualTo(RecommendationPriority.MEDIUM);
            assertThat(recommendation.estimatedImpact()).isEqualTo(2);
        });
    }

    @Test
    void databaseRulePriorityIsMediumWithoutDatabaseEvidence(@TempDir Path root) throws Exception {
        ContextReport context = new ContextReport(true, 10, true, true, true, 1, 1,
                true, false, true, true, Map.of(), List.of());
        RepositoryReport repository = report(root, context, true, 10, 1, Map.of(), Set.of());

        Recommendation database = find(evaluate(root, repository, defaults(), true).recommendations(),
                "context.database-rules.missing");

        assertThat(database.priority()).isEqualTo(RecommendationPriority.MEDIUM);
    }

    @Test
    void recommendationRejectsEmptyEvidence() {
        assertThatThrownBy(() -> new Recommendation("id", RecommendationCategory.CONTEXT,
                RecommendationPriority.LOW, "title", "reason", "action", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    private io.agentforge.model.RecommendationReport evaluate(Path root, RepositoryReport repository,
                                                               PolicyConfig config, boolean policiesPresent) {
        var scores = readiness.evaluate(root, repository.repository(), repository.structure(), repository.context(),
                repository.architecture(), config, policiesPresent);
        return engine.evaluate(root, repository, scores, config, policiesPresent);
    }

    private RepositoryReport impLike(Path root, ContextReport context, boolean contract, int sources, int tests) {
        return report(root, context, contract, sources, tests,
                Map.of(JavaRole.CONTROLLER, 89, JavaRole.SERVICE, 103, JavaRole.MAPPER, 86),
                Set.of(DatabaseTechnology.MYSQL));
    }

    private RepositoryReport report(Path root, ContextReport context, boolean contract, int sources, int tests,
                                    Map<JavaRole, Integer> roles, Set<DatabaseTechnology> databases) {
        RepositoryInfo repository = new RepositoryInfo(root, true, root, "main", "abc", false, List.of());
        RepositoryStructure structure = new RepositoryStructure(List.of("."),
                Map.of(FileType.JAVA, sources, FileType.SQL, databases.isEmpty() ? 0 : 2), sources, tests, List.of());
        ProjectTechnology technology = new ProjectTechnology(Set.of(BuildSystem.MAVEN),
                Set.of(BackendFramework.SPRING_BOOT), Set.of(), databases);
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), roles, Map.of(),
                List.of(), List.of(), contract, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        return new RepositoryReport(repository, technology, structure, context, architecture);
    }

    private ContextReport missingContext() {
        return new ContextReport(false, 0, true, false, true, 0, 0,
                false, false, false, false, Map.of(), List.of());
    }

    private ContextReport completeContext(int agentsLines) {
        return new ContextReport(true, agentsLines, true, true, true, 1, 1,
                true, true, true, true, Map.of(), List.of());
    }

    private PolicyConfig defaults() {
        return new ConfigLoader().loadPolicy(Path.of("missing-recommendation-test-root")).config();
    }

    private Recommendation find(List<Recommendation> recommendations, String id) {
        return recommendations.stream().filter(recommendation -> recommendation.id().equals(id)).findFirst().orElseThrow();
    }
}
