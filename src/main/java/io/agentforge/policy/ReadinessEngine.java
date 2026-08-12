package io.agentforge.policy;

import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ContextReport;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.ScoreBreakdown;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReadinessEngine {
    public List<ScoreBreakdown> evaluate(Path root, RepositoryInfo repository, RepositoryStructure structure,
                                         ContextReport context, ArchitectureReport architecture,
                                         PolicyConfig config, boolean policiesPresent) {
        return List.of(
                context(context, config.readiness().context()),
                architecture(structure, architecture, context, config.readiness().architecture()),
                changeSafety(root, repository, context, config.readiness().changeSafety(), policiesPresent),
                testability(structure, context, config.readiness().testability()),
                documentation(context, config.readiness().documentation())
        );
    }

    private ScoreBreakdown context(ContextReport report, PolicyConfig.ContextWeights weights) {
        ScoreBuilder score = new ScoreBuilder("Context Quality");
        score.add(report.agentsPresent(), weights.agents(), "AGENTS.md");
        score.add(report.readmePresent(), weights.readme(), "README");
        score.add(report.skillsCount() > 0, weights.skills(), "Skills");
        score.add(report.agentRulesCount() > 0, weights.agentRules(), "Agent rules");
        score.add(report.gitRulesPresent(), weights.gitRules(), "Git rules");
        score.add(report.databaseRulesPresent(), weights.databaseRules(), "Database rules");
        score.add(report.apiRulesPresent(), weights.apiRules(), "API contract rules");
        score.add(report.testingRulesPresent(), weights.testingRules(), "Testing rules");
        if (report.agentsPresent() && report.agentsLines() > weights.agentsMaxLines()) {
            score.penalty(weights.agentsTooLargePenalty(), "AGENTS.md exceeds " + weights.agentsMaxLines() + " lines");
        }
        return score.build();
    }

    private ScoreBreakdown architecture(RepositoryStructure structure, ArchitectureReport report, ContextReport context,
                                        PolicyConfig.ArchitectureWeights weights) {
        ScoreBuilder score = new ScoreBuilder("Architecture Clarity");
        score.add(context.architectureDocumentPresent(), weights.architectureDoc(), "Architecture document");
        score.add(report.contractConfigured(), weights.architectureContract(), "Architecture contract");
        score.add(!structure.modules().isEmpty() || structure.sourceFiles() > 0, weights.recognizableStructure(), "Recognizable project structure");
        score.add(report.possibleLayerBypasses().isEmpty(), weights.noLayerBypass(), "No detectable layer bypass");
        return score.build();
    }

    private ScoreBreakdown changeSafety(Path root, RepositoryInfo repository, ContextReport context,
                                        PolicyConfig.ChangeSafetyWeights weights, boolean policiesPresent) {
        ScoreBuilder score = new ScoreBuilder("Change Safety");
        score.add(policiesPresent, weights.policies(), "AgentForge policies");
        score.add(Files.isRegularFile(root.resolve(".gitignore")), weights.gitignore(), ".gitignore");
        score.add(repository.gitRepository(), weights.gitRepository(), "Git repository");
        score.add(context.gitRulesPresent(), weights.gitRules(), "Git rules");
        score.add(context.databaseRulesPresent(), weights.databaseRules(), "Database rules");
        score.add(context.apiRulesPresent(), weights.apiRules(), "API rules");
        return score.build();
    }

    private ScoreBreakdown testability(RepositoryStructure structure, ContextReport context,
                                       PolicyConfig.TestabilityWeights weights) {
        ScoreBuilder score = new ScoreBuilder("Testability");
        score.add(structure.testFiles() > 0, weights.testsPresent(), "Tests present");
        double ratio = structure.testFileRatio();
        if (ratio >= weights.minimumTestRatio()) {
            score.addWithFailure(true, weights.testRatio(),
                    "Test file ratio >= " + percentage(weights.minimumTestRatio(), 0), "");
        } else {
            score.addWithFailure(false, weights.testRatio(), "",
                    "Test file ratio below " + percentage(weights.minimumTestRatio(), 0)
                            + " (current " + percentage(ratio, 2) + ")");
        }
        score.add(context.testingRulesPresent(), weights.testingRules(), "Testing rules");
        return score.build();
    }

    private ScoreBreakdown documentation(ContextReport context, PolicyConfig.DocumentationWeights weights) {
        ScoreBuilder score = new ScoreBuilder("Documentation");
        score.add(context.readmePresent(), weights.readme(), "README");
        score.add(context.architectureDocumentPresent(), weights.architectureDoc(), "Architecture document");
        score.add(context.agentsPresent(), weights.agents(), "AGENTS.md");
        score.add(context.docsDirectoryPresent(), weights.docsDirectory(), "docs directory");
        return score.build();
    }

    private static final class ScoreBuilder {
        private final String name;
        private final List<String> details = new ArrayList<>();
        private int score;

        private ScoreBuilder(String name) { this.name = name; }

        private void add(boolean present, int points, String label) {
            if (present) {
                score += points;
                details.add("+" + points + " " + label);
            } else {
                details.add("+0 " + label + " missing");
            }
        }

        private void penalty(int points, String label) {
            score -= points;
            details.add("-" + points + " " + label);
        }

        private void addWithFailure(boolean present, int points, String successLabel, String failureLabel) {
            if (present) {
                score += points;
                details.add("+" + points + " " + successLabel);
            } else {
                details.add("+0 " + failureLabel);
            }
        }

        private ScoreBreakdown build() {
            return new ScoreBreakdown(name, Math.max(0, Math.min(100, score)), details);
        }
    }

    public static int overallScore(List<ScoreBreakdown> scores) {
        return (int) Math.round(scores.stream().mapToInt(ScoreBreakdown::score).average().orElse(0));
    }

    private String percentage(double ratio, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f%%", ratio * 100);
    }
}
