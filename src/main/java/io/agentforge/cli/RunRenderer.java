package io.agentforge.cli;

import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.VerificationFinding;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Deterministic formatting of aggregate run facts; raw prompts and process streams are intentionally omitted. */
@Component
public class RunRenderer {
    public String render(AgentRunResult result) {
        StringBuilder out = new StringBuilder("AgentForge Run\n");
        section(out, "Repository");
        row(out, "Path", result.repositoryRoot());

        section(out, "Task");
        row(out, "Task", result.task());
        row(out, "Task Type", result.taskType());
        row(out, "Agent", result.agent().type());

        section(out, "Engineering Boundary");
        row(out, "PRIMARY", result.verificationReport().executionBoundary().primaryPaths().size());
        row(out, "SECONDARY", result.verificationReport().executionBoundary().secondaryPaths().size());
        row(out, "REFERENCE", result.verificationReport().executionBoundary().referencePaths().size());

        section(out, "Agent Execution");
        row(out, "Status", result.agentExecutionResult().status());
        row(out, "Exit Code", result.agentExecutionResult().processExitCode() == null
                ? "N/A" : result.agentExecutionResult().processExitCode());
        row(out, "stdout Captured", present(result.agentExecutionResult().stdout()));
        row(out, "stderr Captured", present(result.agentExecutionResult().stderr()));
        if (!result.agentExecutionResult().failureMessage().isBlank()) {
            row(out, "Failure Detail", result.agentExecutionResult().failureMessage());
        }

        section(out, "Execution Delta");
        row(out, "Files", result.executionChangeReport().changes().size());
        if (result.executionChangeReport().changes().isEmpty()) {
            out.append("None\n");
        } else {
            result.executionChangeReport().changes().forEach(change -> renderChange(out, change));
        }

        section(out, "Verification");
        row(out, "Overall", result.verificationReport().overallStatus());
        row(out, "Scope", result.verificationReport().scopeStatus());
        row(out, "Policy", result.verificationReport().policyStatus());
        row(out, "Task-Type", result.verificationReport().taskTypeStatus());
        row(out, "Architecture", result.verificationReport().executionBoundary().contractConfigured()
                ? result.verificationReport().architectureStatus() : "NOT CONFIGURED");

        section(out, "Findings");
        if (result.verificationReport().findings().isEmpty()) {
            out.append("None\n");
        } else {
            for (VerificationFinding finding : result.verificationReport().findings()) {
                out.append(finding.severity()).append(" [").append(finding.category()).append("] ")
                        .append(finding.code()).append(' ').append(finding.path()).append(" — ")
                        .append(finding.message()).append('\n');
            }
        }

        section(out, "Decision");
        out.append("AGENT RUN ").append(decision(result.status())).append('\n');
        return out.toString();
    }

    private void renderChange(StringBuilder out, ExecutionChangeReport.ExecutionChange change) {
        out.append(change.type()).append(' ');
        if (change.oldPath() != null) out.append(change.oldPath()).append(" -> ");
        out.append(change.path());
        if (change.contentChanged()) out.append(" CONTENT_CHANGED");
        if (change.gitStateChanged()) out.append(" GIT_STATE_CHANGED");
        out.append('\n');
    }

    private String decision(AgentRunStatus status) {
        return switch (status) {
            case PASS -> "PASS";
            case WARNING -> "WARNING";
            case VERIFICATION_FAILED -> "VERIFICATION FAILED";
            case AGENT_FAILED -> "AGENT FAILED";
            case AGENT_TIMED_OUT -> "TIMED OUT";
            case AGENT_INTERRUPTED -> "INTERRUPTED";
            case AGENT_START_FAILED -> "START FAILED";
        };
    }

    private String present(String output) {
        return output.isEmpty() ? "NO" : "YES";
    }

    private void section(StringBuilder out, String title) {
        out.append('\n').append(title).append('\n').append("----------------------------------\n");
    }

    private void row(StringBuilder out, String label, Object value) {
        out.append(String.format(Locale.ROOT, "%-24s %s%n", label, value));
    }
}
