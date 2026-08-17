package io.agentforge.runtrace;

import io.agentforge.model.AgentRunResult;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.VerificationFinding;
import org.springframework.stereotype.Component;

@Component
public class RunMarkdownRenderer {
    public String render(RunTraceReference trace, AgentRunResult result, String promptSha) {
        StringBuilder out = new StringBuilder("# AgentForge Run Report\n\n");
        out.append("## Run\n\nRun ID: `").append(trace.runId()).append("`\n\nRepository: `")
                .append(result.repositoryRoot()).append("`\n\nAgent: `").append(result.agent().type()).append("`\n\n");
        out.append("## Question\n\n").append(result.task()).append("\n\n");
        out.append("## Engineering Boundary\n\nTask Type: `").append(result.taskType()).append("`\n\n")
                .append("Task Type Confidence: `").append(result.plan().taskClassification().typeConfidence())
                .append("`  \nMutation Mode: `").append(result.plan().taskClassification().mutationMode())
                .append("`  \nSecurity Impact: `").append(result.plan().taskClassification().securityImpact())
                .append("`  \nClassification Source: `").append(result.plan().taskClassification().source())
                .append("`\n\n")
                .append("PRIMARY: ").append(result.verificationReport().executionBoundary().primaryPaths().size())
                .append("  \nSECONDARY: ").append(result.verificationReport().executionBoundary().secondaryPaths().size())
                .append("  \nREFERENCE: ").append(result.verificationReport().executionBoundary().referencePaths().size())
                .append("\n\n");
        result.verificationReport().intentConsistency().ifPresent(consistency -> out
                .append("## Task Intent\n\nGoal: ").append(consistency.taskSpec().goal())
                .append("\n\nExpected Scopes: ").append(consistency.taskSpec().expectedChanges().size())
                .append("\n\nForbidden Changes: ").append(consistency.taskSpec().forbiddenChanges().size())
                .append("\n\n"));
        out.append("## Prompt Evidence\n\nPrompt File: `prompt.md`\n\nPrompt SHA-256: `")
                .append(promptSha).append("`\n\n");
        out.append("## Agent Execution\n\nStatus: `").append(result.agentExecutionResult().status())
                .append("`\n\nExit Code: `").append(result.agentExecutionResult().processExitCode() == null
                        ? "N/A" : result.agentExecutionResult().processExitCode()).append("`\n\n");
        out.append("## Agent Output\n\n");
        if (result.agentExecutionResult().stdout().isEmpty()) out.append("_No agent output was produced._\n");
        else out.append(result.agentExecutionResult().stdout())
                .append(result.agentExecutionResult().stdout().endsWith("\n") ? "" : "\n");
        out.append("\n## Execution Delta\n\nFiles: ").append(result.executionChangeReport().changes().size()).append("\n\n");
        for (ExecutionChangeReport.ExecutionChange change : result.executionChangeReport().changes()) {
            out.append("- `").append(change.type()).append("` `").append(change.path()).append("`\n");
        }
        if (result.executionChangeReport().changes().isEmpty()) out.append("None\n");
        out.append("\n## AgentForge Verification\n\nOverall: `").append(result.verificationReport().overallStatus())
                .append("`  \nScope: `").append(result.verificationReport().scopeStatus())
                .append("`  \nPolicy: `").append(result.verificationReport().policyStatus())
                .append("`  \nTask-Type: `").append(result.verificationReport().taskTypeStatus())
                .append("`  \nArchitecture: `").append(result.verificationReport().executionBoundary().contractConfigured()
                        ? result.verificationReport().architectureStatus() : "NOT_CONFIGURED").append("`\n\n### Findings\n\n");
        result.verificationReport().intentConsistency().ifPresent(consistency -> out
                .append("Intent Consistency: `").append(consistency.status()).append("`  \n")
                .append("Unexpected: ").append(consistency.unexpectedFiles().size()).append("  \n")
                .append("Mismatches: ").append(consistency.violations().size()).append("\n\n"));
        if (result.verificationReport().findings().isEmpty()) out.append("None\n");
        for (VerificationFinding finding : result.verificationReport().findings()) {
            out.append("- `").append(finding.code()).append("` ").append(finding.path()).append(" — ")
                    .append(finding.message()).append("\n");
        }
        out.append("\n## Decision\n\n`AGENT RUN ").append(result.status()).append("`\n\n")
                .append("## Evidence Files\n\n- `question.md`\n- `task-spec.json`\n- `prompt.md`\n- `agent-output.md`\n")
                .append("- `agent-stderr.log`\n- `run.json`\n");
        return out.toString().replace("\r\n", "\n");
    }
}
