package io.agentforge.cli;

import io.agentforge.application.InitService;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.AuditReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DiffAuditReport;
import io.agentforge.model.FileType;
import io.agentforge.model.JavaRole;
import io.agentforge.model.MatchEvidence;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.Recommendation;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.ScoreBreakdown;
import io.agentforge.model.TaskInspectionReport;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class TextReportRenderer {
    public String inspect(TaskInspectionReport report) {
        RepositoryReport repository = report.repositoryReport();
        StringBuilder out = new StringBuilder();
        out.append("╭─────────────────────────────────╮\n")
                .append("│ AgentForge Task Inspection      │\n")
                .append("╰─────────────────────────────────╯\n\n");
        section(out, "Task");
        out.append(report.request().task()).append('\n');
        row(out, "Core Keywords", joinedOrNone(report.taskAnalysis().coreTokens()));
        row(out, "Task Phrase", joinedOrNone(report.taskAnalysis().exactPhrases()));
        row(out, "Related Phrases", joinedOrNone(meaningfulDerivedPhrases(report)));
        section(out, "Repository");
        row(out, "Path", repository.repository().path());
        technology(out, repository.technology());
        section(out, "Relevant Files");
        if (report.relevantFiles().isEmpty()) {
            out.append("No high-confidence relevant files found.\n")
                    .append("Suggestions:\n")
                    .append("- use a more specific class, module, or business keyword\n");
        } else {
            int index = 1;
            for (var file : report.relevantFiles()) {
                out.append(index++).append(". [").append(file.level()).append("] ")
                        .append(file.className().orElseGet(() -> fileName(file.path()))).append('\n');
                row(out, "   Path", file.path());
                row(out, "   Score", file.score());
                row(out, "   Confidence", file.level());
                row(out, "   Match", file.matchedCoreTokens().size() + "/" + file.totalCoreTokens()
                        + " core keywords");
                row(out, "   Module", file.module());
                row(out, "   Responsibility", file.responsibility());
                row(out, "   Confidence Reason", file.confidence().reason());
                out.append("   Evidence:\n");
                evidenceGroup(out, "Task", file.evidence(), "CORE_TOKEN", "MULTI_CORE",
                        "FULL_CORE_COVERAGE", "EXACT_PHRASE", "DERIVED_NGRAM");
                evidenceGroup(out, "Structure", file.evidence(), "CLASS_OR_FILE", "SOURCE_AREA", "IMPORT",
                        "DIRECT_DEPENDENCY_TYPE", "JAVA_ROLE");
                evidenceGroup(out, "Dependency", file.evidence(), "DEPENDENCY", "ARCHITECTURE_NEIGHBOR");
                evidenceGroup(out, "Context", file.evidence(), "CONTEXT", "SOURCE_AREA_COHORT");
                if (!file.missingCoreTokens().isEmpty()) {
                    out.append("   Missing Core Tokens:\n")
                            .append("   - ").append(String.join(", ", file.missingCoreTokens())).append('\n');
                }
                if (file.responsibility().passive() && file.level() == io.agentforge.model.RelevanceLevel.LOW
                        && file.confidence().reason().startsWith("Confidence is capped")) {
                    out.append("   Relevance Note:\n")
                            .append("   - ").append(file.confidence().reason()).append('\n');
                }
                out.append('\n');
            }
        }
        section(out, "Related Build Modules");
        if (report.relevantModules().isEmpty()) out.append("None\n");
        else report.relevantModules().forEach(module -> out.append("- ").append(module.path())
                .append(" (score ").append(module.score()).append(", files ")
                .append(module.relevantFiles().size()).append(")\n"));
        section(out, "Related Packages / Source Areas");
        if (report.relevantSourceAreas().isEmpty()) out.append("None\n");
        else report.relevantSourceAreas().forEach(area -> out.append("- ").append(area.name())
                .append(" (score ").append(area.score()).append(", files ")
                .append(area.relevantFiles().size()).append(")\n"));
        section(out, "Related Flow");
        if (report.relatedFlow().isEmpty()) out.append("None detected\n");
        else report.relatedFlow().forEach(edge -> out.append(simpleName(edge.from())).append(" -> ")
                .append(simpleName(edge.to())).append('\n'));
        if (!report.warnings().isEmpty()) {
            section(out, "Warnings");
            report.warnings().forEach(warning -> out.append("WARN ").append(warning).append('\n'));
        }
        return out.toString();
    }

    private static List<String> meaningfulDerivedPhrases(TaskInspectionReport report) {
        List<String> core = report.taskAnalysis().coreTokens();
        java.util.LinkedHashSet<String> phrases = new java.util.LinkedHashSet<>();
        for (int index = 0; index + 1 < core.size(); index++) {
            String compact = core.get(index) + core.get(index + 1);
            String spaced = core.get(index) + " " + core.get(index + 1);
            if (report.taskAnalysis().derivedTokens().contains(compact)) phrases.add(compact);
            else if (report.taskAnalysis().derivedTokens().contains(spaced)) phrases.add(spaced);
        }
        return List.copyOf(phrases);
    }

    private static void evidenceGroup(StringBuilder out, String label, List<MatchEvidence> evidence,
                                      String... signals) {
        Set<String> accepted = Set.of(signals);
        List<MatchEvidence> items = evidence.stream()
                .filter(item -> item.points() > 0 && accepted.contains(item.signal())).toList();
        if (items.isEmpty()) return;
        out.append("   ").append(label).append(":\n");
        items.forEach(item -> out.append("   - +").append(item.points()).append(' ')
                .append(evidenceDetail(item)).append('\n'));
    }

    private static String evidenceDetail(MatchEvidence evidence) {
        if (evidence.signal().equals("DERIVED_NGRAM")) return "Weak lexical recall signal";
        return evidence.detail();
    }

    public String audit(AuditReport audit) {
        RepositoryReport report = audit.repositoryReport();
        ContextReport context = report.context();
        ArchitectureReport architecture = report.architecture();
        StringBuilder out = new StringBuilder();
        out.append("╭─────────────────────────────────╮\n")
                .append("│ AgentForge Repository Audit     │\n")
                .append("╰─────────────────────────────────╯\n\n");
        section(out, "Repository");
        row(out, "Path", report.repository().path());
        row(out, "Git", yesNo(report.repository().gitRepository()));
        row(out, "Branch", report.repository().branch());
        row(out, "HEAD", shortHead(report.repository().head()));
        section(out, "Project");
        technology(out, report.technology());
        section(out, "Repository Structure");
        row(out, "Modules", report.structure().modules().size());
        row(out, "Java Files", report.structure().count(FileType.JAVA));
        int frontendFiles = report.structure().count(FileType.JAVASCRIPT) + report.structure().count(FileType.JSX)
                + report.structure().count(FileType.TYPESCRIPT) + report.structure().count(FileType.TSX)
                + report.structure().count(FileType.VUE);
        row(out, "Frontend Files", frontendFiles);
        row(out, "SQL Files", report.structure().count(FileType.SQL));
        section(out, "AI Context");
        row(out, "AGENTS.md", context.agentsPresent() ? "FOUND (" + context.agentsLines() + " lines)" : "MISSING");
        row(out, "README.md", context.readmePresent() ? "FOUND" : "MISSING");
        row(out, "Architecture Doc", context.architectureDocumentPresent() ? "FOUND" : "MISSING");
        row(out, "Skills", context.skillsCount());
        row(out, "Agent Rules", context.agentRulesCount());
        section(out, "Engineering Rules");
        row(out, "Git Rules", found(context.gitRulesPresent()));
        row(out, "Database Rules", found(context.databaseRulesPresent()));
        row(out, "API Rules", found(context.apiRulesPresent()));
        row(out, "Test Rules", found(context.testingRulesPresent()));
        section(out, "Architecture");
        row(out, "Analysis Mode", architecture.analysisMode());
        row(out, "Contract", architecture.contractConfigured() ? "CONFIGURED" : "NOT CONFIGURED");
        row(out, "Controller", architecture.roleCounts().getOrDefault(JavaRole.CONTROLLER, 0));
        row(out, "Service", architecture.roleCounts().getOrDefault(JavaRole.SERVICE, 0));
        row(out, "Mapper", architecture.roleCounts().getOrDefault(JavaRole.MAPPER, 0));
        row(out, "Repository", architecture.roleCounts().getOrDefault(JavaRole.REPOSITORY, 0));
        architecture.relationCounts().forEach((relation, count) -> row(out, relation, count));
        architecture.possibleLayerBypasses().forEach(edge -> out.append("WARN Possible Layer Bypass: ")
                .append(edge.from()).append(" -> ").append(edge.to()).append('\n'));
        architecture.contractViolations().forEach(edge -> out.append("WARN Architecture Contract Violation: ")
                .append(edge.from()).append(" -> ").append(edge.to()).append('\n'));
        section(out, "Testability");
        row(out, "Source Files", report.structure().sourceFiles());
        row(out, "Test Files", report.structure().testFiles());
        row(out, "Test File Ratio", percentage(report.structure().testFileRatio(), 2));
        row(out, "Recommended Threshold", percentage(audit.testFileRatioThreshold(), 0));
        section(out, "Potential Risks");
        if (audit.warnings().isEmpty()) out.append("None\n");
        else audit.warnings().forEach(warning -> out.append("WARN ").append(warning).append('\n'));
        section(out, "AI Agent Readiness");
        audit.readinessScores().forEach(score -> {
            row(out, score.name(), score.score());
            score.details().forEach(detail -> out.append("  ").append(detail).append('\n'));
        });
        out.append('\n');
        row(out, "Overall Score", audit.overallScore() + " / 100");
        section(out, "Top Recommendations");
        if (audit.recommendationReport().recommendations().isEmpty()) {
            out.append("No high-value recommendations.\n");
        } else {
            int index = 1;
            for (Recommendation recommendation : audit.recommendationReport().recommendations().stream().limit(5).toList()) {
                out.append(index++).append(". [").append(recommendation.priority()).append("] ")
                        .append(recommendation.title()).append('\n');
                out.append("   Why:\n");
                indented(out, recommendation.reason(), "   ");
                out.append("   Action:\n");
                indented(out, recommendation.action(), "   ");
                out.append("   Evidence:\n");
                recommendation.evidence().forEach(item -> out.append("   - ").append(item).append('\n'));
                out.append("   Estimated Overall Impact: +").append(recommendation.estimatedImpact()).append("\n\n");
            }
        }
        return out.toString();
    }

    public String diff(DiffAuditReport report) {
        ChangeReport change = report.changeReport();
        StringBuilder out = new StringBuilder("AgentForge Change Audit\n");
        section(out, "Git");
        row(out, "Branch", change.repository().branch());
        row(out, "HEAD", shortHead(change.repository().head()));
        section(out, "Changes");
        row(out, "Files", change.files().size());
        row(out, "Added", change.count(ChangeStatus.ADDED));
        row(out, "Modified", change.count(ChangeStatus.MODIFIED));
        row(out, "Deleted", change.count(ChangeStatus.DELETED));
        row(out, "Renamed", change.count(ChangeStatus.RENAMED));
        row(out, "Untracked", change.count(ChangeStatus.UNTRACKED));
        section(out, "Modules");
        if (change.modules().isEmpty()) out.append("None\n");
        else change.modules().forEach(module -> out.append(module).append('\n'));
        section(out, "Policy");
        row(out, "Policy Source", report.defaultPolicyUsed() ? "BUILT-IN DEFAULTS" : ".agentforge/policies.yaml");
        if (report.defaultPolicyUsed()) out.append("WARN No .agentforge/policies.yaml; built-in defaults used\n");
        row(out, "Max Files", pass(!hasRule(report, "change.max_files")));
        row(out, "Protected Files", pass(!hasRule(report, "change.protected")));
        row(out, "Denied Files", pass(!hasRule(report, "change.deny")));
        row(out, "Architecture", report.architectureReport().contractConfigured()
                ? pass(report.architectureReport().contractViolations().isEmpty()) : "NOT CONFIGURED");
        if (report.policyReport().violations().isEmpty()) out.append("No policy violations\n");
        else report.policyReport().violations().forEach(violation -> out.append(violation.severity()).append(' ')
                .append(violation.rule()).append(' ').append(violation.path()).append(" — ").append(violation.message()).append('\n'));
        section(out, "Architecture Findings In Changed Files");
        row(out, "Contract", report.architectureReport().contractConfigured() ? "CONFIGURED" : "NOT CONFIGURED");
        if (report.architectureReport().possibleLayerBypasses().isEmpty()
                && report.architectureReport().contractViolations().isEmpty()) {
            out.append("None\n");
        } else {
            report.architectureReport().possibleLayerBypasses().forEach(edge -> out.append("WARN Possible Layer Bypass: ")
                    .append(edge.from()).append(" -> ").append(edge.to()).append('\n'));
            report.architectureReport().contractViolations().forEach(edge -> out.append("WARN Architecture Contract Violation: ")
                    .append(edge.from()).append(" -> ").append(edge.to()).append('\n'));
        }
        section(out, "Change Signals");
        row(out, "Config Change", yesNo(change.configChange()));
        row(out, "Dependency Change", yesNo(change.dependencyChange()));
        row(out, "Database Change", yesNo(change.databaseChange()));
        row(out, "Security Change", yesNo(change.securityChange()));
        row(out, "AGENTS Change", yesNo(change.agentsChange()));
        section(out, "Minimal Change");
        row(out, "Score", report.minimalChange().score() + " / 100");
        report.minimalChange().details().forEach(detail -> out.append(detail).append('\n'));
        section(out, "Risk Factors");
        if (report.riskReport().factors().isEmpty()) out.append("None\n");
        else report.riskReport().factors().forEach(factor -> out.append('+').append(factor.points()).append(' ')
                .append(factor.reason()).append('\n'));
        out.append('\n');
        row(out, "Risk Score", report.riskReport().score());
        row(out, "Level", report.riskReport().level());
        return out.toString();
    }

    public String init(InitService.InitResult result) {
        StringBuilder out = new StringBuilder("AgentForge Initialize\n");
        section(out, "Repository");
        row(out, "Path", result.path());
        section(out, "Created");
        if (result.created().isEmpty()) out.append("None\n");
        else result.created().forEach(file -> out.append("CREATED ").append(file).append('\n'));
        section(out, "Preserved");
        if (result.skipped().isEmpty()) out.append("None\n");
        else result.skipped().forEach(file -> out.append("SKIPPED ").append(file).append('\n'));
        return out.toString();
    }

    private boolean hasRule(DiffAuditReport report, String rule) {
        return report.policyReport().violations().stream().anyMatch(violation -> violation.rule().equals(rule));
    }

    private void technology(StringBuilder out, ProjectTechnology technology) {
        row(out, "Backend", names(technology.backendFrameworks()));
        row(out, "Frontend", names(technology.frontendFrameworks()));
        row(out, "Build", names(technology.buildSystems()));
        row(out, "Database", names(technology.databases()));
    }

    private String names(Collection<? extends Enum<?>> values) {
        if (values.isEmpty()) return "Unknown";
        return values.stream().map(value -> value.name().replace('_', ' '))
                .sorted().collect(Collectors.joining(", "));
    }

    private String joinedOrNone(Collection<String> values) {
        return values.isEmpty() ? "None" : String.join(", ", values);
    }

    private void section(StringBuilder out, String title) {
        out.append('\n').append(title).append('\n').append("----------------------------------\n");
    }

    private void row(StringBuilder out, String label, Object value) {
        out.append(String.format(Locale.ROOT, "%-24s %s%n", label, value));
    }

    private String yesNo(boolean value) { return value ? "Yes" : "No"; }
    private String found(boolean value) { return value ? "FOUND" : "MISSING"; }
    private String pass(boolean value) { return value ? "PASS" : "FAIL"; }
    private String shortHead(String head) { return head != null && head.length() > 12 ? head.substring(0, 12) : head; }
    private String percentage(double ratio, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f%%", ratio * 100);
    }
    private void indented(StringBuilder out, String value, String indentation) {
        value.lines().forEach(line -> out.append(indentation).append(line).append('\n'));
    }

    private String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }
}
