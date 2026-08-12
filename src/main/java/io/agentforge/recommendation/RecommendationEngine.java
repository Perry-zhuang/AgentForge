package io.agentforge.recommendation;

import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DatabaseTechnology;
import io.agentforge.model.FileType;
import io.agentforge.model.JavaRole;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.Recommendation;
import io.agentforge.model.RecommendationCategory;
import io.agentforge.model.RecommendationPriority;
import io.agentforge.model.RecommendationReport;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.policy.ReadinessEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RecommendationEngine {
    private static final Comparator<Recommendation> ORDER = Comparator
            .comparing(Recommendation::priority)
            .thenComparing(Recommendation::estimatedImpact, Comparator.reverseOrder())
            .thenComparing(Recommendation::id);

    private final ReadinessEngine readinessEngine;

    public RecommendationEngine(ReadinessEngine readinessEngine) {
        this.readinessEngine = readinessEngine;
    }

    public RecommendationReport evaluate(Path root, RepositoryReport repository, List<ScoreBreakdown> currentScores,
                                         PolicyConfig config, boolean policiesPresent) {
        int currentOverall = ReadinessEngine.overallScore(currentScores);
        State current = new State(repository.structure(), repository.context(), repository.architecture(), policiesPresent);
        List<Recommendation> recommendations = new ArrayList<>();
        ContextReport context = current.context();
        RepositoryStructure structure = current.structure();
        ArchitectureReport architecture = current.architecture();

        if (!context.agentsPresent()) {
            recommendations.add(recommend("context.agents.missing", RecommendationCategory.CONTEXT,
                    RecommendationPriority.HIGH, "Add AGENTS.md",
                    "No repository-level Coding Agent operating rules were detected.",
                    "Run:\nagentforge init " + root + "\nThen review AGENTS.md and customize repository constraints.",
                    List.of("AGENTS.md: missing"), root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), true, 1, null, null, null, null, null))));
        }
        if (!policiesPresent) {
            recommendations.add(recommend("change-safety.policies.missing", RecommendationCategory.CHANGE_SAFETY,
                    RecommendationPriority.HIGH, "Configure AgentForge policies",
                    "Change audits currently rely on built-in defaults rather than repository-specific rules.",
                    "Run:\nagentforge init " + root + "\nThen customize .agentforge/policies.yaml.",
                    List.of(".agentforge/policies.yaml: missing", "Policy source: built-in defaults"),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withPoliciesPresent(true)));
        }
        if (!architecture.contractConfigured()) {
            recommendations.add(recommend("architecture.contract.missing", RecommendationCategory.ARCHITECTURE,
                    RecommendationPriority.HIGH, "Define architecture contract",
                    detectedRoleCount(architecture) > 0
                            ? "Spring layers were detected, but allowed dependency boundaries are not configured."
                            : "No allowed architecture dependency contract is configured.",
                    "Review the detected layer relationships and define .agentforge/architecture.yaml.",
                    architectureEvidence(architecture), root, repository, currentScores, currentOverall, config, current,
                    state -> state.withArchitecture(copyArchitecture(state.architecture(), true))));
        }
        if (!context.architectureDocumentPresent()) {
            recommendations.add(recommend("documentation.architecture.missing", RecommendationCategory.DOCUMENTATION,
                    RecommendationPriority.MEDIUM, "Add architecture documentation",
                    "Coding Agents have no concise architecture overview to consult before changing the repository.",
                    "Create docs/architecture/overview.md with modules, layer responsibilities, and dependency boundaries.",
                    List.of("Architecture document: missing", "Recommended path: docs/architecture/overview.md"),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, null, true, null, null, null, null))));
        }
        if (!context.databaseRulesPresent()) {
            boolean databaseDetected = !repository.technology().databases().isEmpty() || structure.count(FileType.SQL) > 0;
            RecommendationPriority priority = databaseDetected ? RecommendationPriority.HIGH : RecommendationPriority.MEDIUM;
            List<String> evidence = new ArrayList<>();
            evidence.add("Database rules: missing");
            if (!repository.technology().databases().isEmpty()) {
                evidence.add("Database technology: " + databaseNames(repository.technology().databases()));
            }
            if (structure.count(FileType.SQL) > 0) evidence.add("SQL files: " + structure.count(FileType.SQL));
            recommendations.add(recommend("context.database-rules.missing", RecommendationCategory.CONTEXT,
                    priority, "Define database change policy",
                    databaseDetected
                            ? "The repository contains database technology or SQL changes but has no database change rules."
                            : "No repository-level database change rules were detected.",
                    "Document migration, compatibility, review, and rollback requirements in AGENTS.md or docs.",
                    evidence, root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, null, null, null, true, null, null))));
        }
        int controllers = architecture.roleCounts().getOrDefault(JavaRole.CONTROLLER, 0);
        if (controllers > 0 && !context.apiRulesPresent()) {
            recommendations.add(recommend("context.api-rules.missing", RecommendationCategory.CONTEXT,
                    RecommendationPriority.MEDIUM, "Define API compatibility rules",
                    "Controllers were detected, but API compatibility expectations are not documented.",
                    "Document API contract, backward-compatibility, and breaking-change requirements.",
                    List.of("Controllers: " + controllers, "API rules: missing"),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, null, null, null, null, true, null))));
        }
        if (structure.testFiles() > 0 && !context.testingRulesPresent()) {
            recommendations.add(recommend("testability.rules.missing", RecommendationCategory.TESTABILITY,
                    RecommendationPriority.MEDIUM, "Define test requirements",
                    "Tests exist, but Coding Agents are not told which validation commands and checks are required.",
                    "Document required test commands and completion criteria in AGENTS.md or docs.",
                    List.of("Test files: " + structure.testFiles(), "Testing rules: missing"),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, null, null, null, null, null, true))));
        }
        double threshold = config.readiness().testability().minimumTestRatio();
        if (structure.sourceFiles() > 0 && structure.testFileRatio() < threshold) {
            int targetTestFiles = Math.max(structure.testFiles(), (int) Math.ceil(structure.sourceFiles() * threshold));
            recommendations.add(recommend("testability.file-ratio.low", RecommendationCategory.TESTABILITY,
                    RecommendationPriority.MEDIUM, "Improve test file ratio",
                    "The repository has a low test-file-to-source-file signal; this is not a code coverage measurement.",
                    "Add focused automated tests in high-risk modules until the configured test file ratio is met.",
                    List.of("Source files: " + structure.sourceFiles(), "Test files: " + structure.testFiles(),
                            "Test file ratio: " + percentage(structure.testFileRatio(), 2),
                            "Recommended threshold: " + percentage(threshold, 0)),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withStructure(copyStructure(state.structure(), targetTestFiles))));
        }
        int maxAgentsLines = config.readiness().context().agentsMaxLines();
        if (context.agentsPresent() && context.agentsLines() > maxAgentsLines) {
            recommendations.add(recommend("context.agents.too-large", RecommendationCategory.CONTEXT,
                    RecommendationPriority.MEDIUM, "Reduce AGENTS.md size",
                    "A very large AGENTS.md makes stable repository constraints harder for Coding Agents to navigate.",
                    "Move detailed rules to docs or skills and keep AGENTS.md as navigation plus stable constraints.",
                    List.of("AGENTS.md lines: " + context.agentsLines(), "Configured maximum: " + maxAgentsLines),
                    root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, maxAgentsLines, null, null, null, null, null))));
        }
        if (context.skillsCount() == 0) {
            recommendations.add(recommend("context.skills.missing", RecommendationCategory.CONTEXT,
                    RecommendationPriority.LOW, "Consider adding reusable repository skills",
                    "No reusable repository skills were detected; skills are optional but can capture repeatable workflows.",
                    "Add a focused skills/<name>/SKILL.md only for stable, repeatable repository procedures.",
                    List.of("Skills: 0"), root, repository, currentScores, currentOverall, config, current,
                    state -> state.withContext(copyContext(state.context(), null, null, null, 1, null, null, null))));
        }

        recommendations.sort(ORDER);
        return new RecommendationReport(recommendations);
    }

    private Recommendation recommend(String id, RecommendationCategory category, RecommendationPriority priority,
                                     String title, String reason, String action, List<String> evidence,
                                     Path root, RepositoryReport repository, List<ScoreBreakdown> currentScores,
                                     int currentOverall, PolicyConfig config, State current,
                                     UnaryOperator<State> improvement) {
        State improved = improvement.apply(current);
        List<ScoreBreakdown> improvedScores = readinessEngine.evaluate(root, repository.repository(), improved.structure(),
                improved.context(), improved.architecture(), config, improved.policiesPresent());
        int impact = Math.max(0, ReadinessEngine.overallScore(improvedScores) - currentOverall);
        return new Recommendation(id, category, priority, title, reason, action, impact, evidence);
    }

    private ContextReport copyContext(ContextReport context, Boolean agentsPresent, Integer agentsLines,
                                      Boolean architectureDocumentPresent, Integer skillsCount,
                                      Boolean databaseRulesPresent, Boolean apiRulesPresent,
                                      Boolean testingRulesPresent) {
        return new ContextReport(
                agentsPresent == null ? context.agentsPresent() : agentsPresent,
                agentsLines == null ? context.agentsLines() : agentsLines,
                context.readmePresent(),
                architectureDocumentPresent == null ? context.architectureDocumentPresent() : architectureDocumentPresent,
                context.docsDirectoryPresent(),
                skillsCount == null ? context.skillsCount() : skillsCount,
                context.agentRulesCount(), context.gitRulesPresent(),
                databaseRulesPresent == null ? context.databaseRulesPresent() : databaseRulesPresent,
                apiRulesPresent == null ? context.apiRulesPresent() : apiRulesPresent,
                testingRulesPresent == null ? context.testingRulesPresent() : testingRulesPresent,
                context.evidence(), context.warnings());
    }

    private ArchitectureReport copyArchitecture(ArchitectureReport architecture, boolean contractConfigured) {
        return new ArchitectureReport(architecture.graph(), architecture.roleCounts(), architecture.relationCounts(),
                architecture.possibleLayerBypasses(), architecture.contractViolations(), contractConfigured,
                architecture.analysisMode(), architecture.warnings());
    }

    private RepositoryStructure copyStructure(RepositoryStructure structure, int testFiles) {
        return new RepositoryStructure(structure.modules(), structure.fileCounts(), structure.sourceFiles(), testFiles,
                structure.warnings());
    }

    private List<String> architectureEvidence(ArchitectureReport architecture) {
        return List.of(
                "Controller: " + architecture.roleCounts().getOrDefault(JavaRole.CONTROLLER, 0),
                "Service: " + architecture.roleCounts().getOrDefault(JavaRole.SERVICE, 0),
                "Mapper: " + architecture.roleCounts().getOrDefault(JavaRole.MAPPER, 0),
                "Architecture contract: NOT CONFIGURED");
    }

    private int detectedRoleCount(ArchitectureReport architecture) {
        return architecture.roleCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    private String databaseNames(Set<DatabaseTechnology> databases) {
        return databases.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
    }

    private String percentage(double ratio, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f%%", ratio * 100);
    }

    private record State(RepositoryStructure structure, ContextReport context, ArchitectureReport architecture,
                         boolean policiesPresent) {
        private State withStructure(RepositoryStructure value) { return new State(value, context, architecture, policiesPresent); }
        private State withContext(ContextReport value) { return new State(structure, value, architecture, policiesPresent); }
        private State withArchitecture(ArchitectureReport value) { return new State(structure, context, value, policiesPresent); }
        private State withPoliciesPresent(boolean value) { return new State(structure, context, architecture, value); }
    }
}
