package io.agentforge.cli;

import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.PromptScopeFile;
import io.agentforge.model.PromptScopeSection;
import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PromptRenderer {
    public String render(EngineeringPrompt prompt) {
        StringBuilder out = new StringBuilder();
        heading(out, "Engineering Task");
        field(out, "Task", prompt.task());
        field(out, "Task Type", prompt.taskType());
        field(out, "Plan Confidence", prompt.planConfidence());

        heading(out, "Repository Context");
        field(out, "Path", prompt.repository().repository().path());
        technology(out, prompt.repository().technology());
        out.append("Warnings:\n");
        bullets(out, prompt.warnings());

        heading(out, "Objective");
        out.append(prompt.objective()).append('\n');

        heading(out, "Allowed Scope");
        renderScope(out, prompt.primaryScope());
        renderScope(out, prompt.secondaryScope());
        renderScope(out, prompt.referenceScope());

        heading(out, "Architecture Constraints");
        bullets(out, prompt.architectureInstructions());

        heading(out, "Change Constraints");
        subheading(out, "Policy");
        bullets(out, prompt.policyInstructions());
        out.append("\nDo Not Touch:\n");
        bullets(out, prompt.doNotTouchPaths());
        subheading(out, "Pre-Implementation Risk");
        field(out, "Risk", prompt.preImplementationRisk().level());
        field(out, "Score", prompt.preImplementationRisk().score());
        out.append("Factors:\n");
        if (prompt.preImplementationRisk().factors().isEmpty()) out.append("- None\n");
        else prompt.preImplementationRisk().factors().forEach(factor -> out.append("- ")
                .append(factor.reason()).append('\n'));
        out.append("Instructions:\n");
        bullets(out, prompt.riskInstructions());

        heading(out, "Existing Working Tree");
        field(out, "Branch", prompt.gitBaseline().branch());
        field(out, "HEAD", prompt.gitBaseline().head());
        field(out, "Working Tree", prompt.gitBaseline().workingTree());
        bullets(out, prompt.workingTreeInstructions());

        heading(out, "Implementation Strategy");
        numbered(out, prompt.changeInstructions());

        heading(out, "Verification");
        numbered(out, prompt.verificationInstructions());

        heading(out, "Acceptance Criteria");
        bullets(out, prompt.acceptanceCriteria());

        heading(out, "Final Report");
        out.append("Report these items:\n\n");
        numbered(out, prompt.finalReportSections());
        out.append("\nAdditional reporting requirements:\n");
        bullets(out, prompt.finalReportInstructions());
        return out.toString();
    }

    private void renderScope(StringBuilder out, PromptScopeSection scope) {
        subheading(out, scope.heading());
        out.append(scope.definition()).append("\n\nInstructions:\n");
        bullets(out, scope.instructions());
        out.append("\nFiles:\n");
        if (scope.files().isEmpty()) {
            out.append("- None\n");
            return;
        }
        int index = 1;
        for (PromptScopeFile file : scope.files()) {
            out.append(index++).append(". ").append(file.name()).append('\n')
                    .append("   ").append(file.path()).append('\n');
        }
    }

    private void technology(StringBuilder out, ProjectTechnology technology) {
        field(out, "Backend", names(technology.backendFrameworks()));
        field(out, "Frontend", names(technology.frontendFrameworks()));
        field(out, "Build", names(technology.buildSystems()));
        field(out, "Database", names(technology.databases()));
    }

    private String names(Collection<? extends Enum<?>> values) {
        if (values.isEmpty()) return "Unknown";
        return values.stream().map(value -> value.name().replace('_', ' '))
                .sorted().collect(Collectors.joining(", "));
    }

    private void heading(StringBuilder out, String title) {
        if (!out.isEmpty()) out.append('\n');
        out.append("# ").append(title).append("\n\n");
    }

    private void subheading(StringBuilder out, String title) {
        int length = out.length();
        if (length > 0 && (length < 2 || out.charAt(length - 2) != '\n')) out.append('\n');
        out.append("## ").append(title).append("\n\n");
    }

    private void field(StringBuilder out, String name, Object value) {
        out.append(name).append(": ").append(value).append('\n');
    }

    private void bullets(StringBuilder out, Collection<String> values) {
        if (values.isEmpty()) out.append("- None\n");
        else values.forEach(value -> out.append("- ").append(value).append('\n'));
    }

    private void numbered(StringBuilder out, Collection<String> values) {
        if (values.isEmpty()) {
            out.append("1. None\n");
            return;
        }
        int index = 1;
        for (String value : values) out.append(index++).append(". ").append(value).append('\n');
    }
}
