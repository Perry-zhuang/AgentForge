package io.agentforge.cli;

import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.VerificationFinding;
import java.util.Locale;
import org.springframework.stereotype.Component;
import io.agentforge.application.AgentRunException;
import io.agentforge.application.RunPreflightException;
import io.agentforge.model.TaskClassification;

/** Deterministic formatting of aggregate run facts and the agent's delivered stdout. */
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

        classification(out, result.plan().taskClassification());
        if (result.plan().runPreflight().status() == io.agentforge.model.RunPreflight.Status.ALLOWED_WITH_WARNING) {
            section(out, "Preflight");
            row(out, "Status", result.plan().runPreflight().status());
            row(out, "Reason", result.plan().runPreflight().reason());
            row(out, "Warning", result.plan().runPreflight().warning());
        }

        section(out, "Engineering Boundary");
        row(out, "PRIMARY", result.verificationReport().executionBoundary().primaryPaths().size());
        row(out, "SECONDARY", result.verificationReport().executionBoundary().secondaryPaths().size());
        row(out, "REFERENCE", result.verificationReport().executionBoundary().referencePaths().size());

        section(out, "Agent Execution");
        row(out, "Status", result.agentExecutionResult().status());
        row(out, "Exit Code", result.agentExecutionResult().processExitCode() == null
                ? "N/A" : result.agentExecutionResult().processExitCode());
        row(out, "stderr Captured", present(result.agentExecutionResult().stderr()));
        if (!result.agentExecutionResult().failureMessage().isBlank()) {
            row(out, "Failure Detail", result.agentExecutionResult().failureMessage());
        }

        section(out, "Agent Output");
        if (result.agentExecutionResult().stdout().isEmpty()) out.append("No agent output produced.\n");
        else out.append(result.agentExecutionResult().stdout())
                .append(result.agentExecutionResult().stdout().endsWith("\n") ? "" : "\n");

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
        if (result.runTrace() != null) {
            section(out, "Run Trace");
            row(out, "Run ID", result.runTrace().runId());
            row(out, "Archive", result.runTrace().archiveDirectory());
            row(out, "Report", result.runTrace().reportFile().getFileName());
            if (result.runTrace().exportedReport() != null) {
                section(out, "Export");
                row(out, "Saved", result.runTrace().exportedReport());
            }
        }
        return out.toString();
    }

    public String renderIncomplete(AgentRunException failure) {
        StringBuilder out = new StringBuilder("AgentForge Run\n");
        failure.agentResult().ifPresent(agent -> {
            section(out, "Agent Execution");
            row(out, "Status", agent.status());
            row(out, "Exit Code", agent.processExitCode() == null ? "N/A" : agent.processExitCode());
            section(out, "Agent Output");
            out.append(agent.stdout().isEmpty() ? "No agent output produced.\n" : agent.stdout()
                    + (agent.stdout().endsWith("\n") ? "" : "\n"));
        });
        section(out, "Execution Delta");
        row(out, "Files", failure.executionDelta().<Object>map(delta -> delta.changes().size())
                .orElse("INCOMPLETE"));
        section(out, "Verification");
        row(out, "Overall", "INCOMPLETE");
        section(out, "Run Trace");
        failure.runTrace().ifPresent(trace -> {
            row(out, "Archive", trace.archiveDirectory());
            row(out, "Lifecycle", "INCOMPLETE");
        });
        row(out, "Failure Stage", failure.phase());
        row(out, "Failure Detail", failure.getMessage());
        section(out, "Decision");
        out.append("AGENT RUN INCOMPLETE\n");
        return out.toString();
    }

    public String renderBlocked(RunPreflightException failure) {
        StringBuilder out = new StringBuilder("AgentForge Run\n");
        classification(out, failure.plan().taskClassification());
        section(out, "Preflight");
        row(out, "Status", failure.preflight().status());
        row(out, "Reason", failure.preflight().reason());
        section(out, "Agent Execution");
        out.append("NOT STARTED\n");
        section(out, "Decision");
        out.append("RUN BLOCKED\n\nNo coding agent was started.\nNo repository changes were made.\n");
        return out.toString();
    }

    private void classification(StringBuilder out, TaskClassification classification) {
        section(out, "Task Classification");
        row(out, "Type", classification.effectiveType());
        row(out, "Type Confidence", classification.typeConfidence());
        row(out, "Mutation Mode", classification.mutationMode());
        row(out, "Security Impact", classification.securityImpact());
        row(out, "Classification Source", classification.source());
        if (classification.source() == io.agentforge.model.ClassificationSource.USER_OVERRIDE) {
            row(out, "Detected Type", classification.detectedType());
        }
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
