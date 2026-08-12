package io.agentforge.cli;

import io.agentforge.model.PolicyViolation;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationReport;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CheckRenderer {
    public String render(VerificationReport report) {
        StringBuilder out = new StringBuilder("AgentForge Check\n");
        section(out, "Repository");
        row(out, "Path", report.plan().repository().repository().path());
        row(out, "Branch", report.diffAudit().changeReport().repository().branch());
        row(out, "HEAD", shortHead(report.diffAudit().changeReport().repository().head()));

        section(out, "Task");
        out.append(report.plan().task()).append('\n');
        row(out, "Task Type", report.plan().taskType());

        section(out, "Verification");
        row(out, "Overall", report.overallStatus());
        row(out, "Baseline", report.baseline());

        section(out, "Current Working Tree");
        row(out, "Plan Baseline", report.plan().gitBaseline().workingTree());
        row(out, "Plan Baseline Changes", report.plan().gitBaseline().available()
                ? report.plan().gitBaseline().changedFiles() : "N/A");
        row(out, "Current Changes", report.diffAudit().changeReport().files().size());
        row(out, "Execution Attribution", "NOT AVAILABLE");

        section(out, "Scope Compliance");
        row(out, "Status", report.scopeStatus());

        section(out, "Policy Compliance");
        row(out, "Status", report.policyStatus());
        row(out, "Policy Source", report.diffAudit().defaultPolicyUsed()
                ? "BUILT-IN DEFAULTS" : ".agentforge/policies.yaml");
        if (report.diffAudit().policyReport().violations().isEmpty()) {
            out.append("None\n");
        } else {
            for (PolicyViolation violation : report.diffAudit().policyReport().violations()) {
                out.append(violation.severity()).append(' ').append(violation.rule()).append(' ')
                        .append(violation.path()).append(" — ").append(violation.message()).append('\n');
            }
        }

        section(out, "Task-Type Compliance");
        row(out, "Status", report.taskTypeStatus());

        section(out, "Architecture Compliance");
        row(out, "Status", report.plan().repository().architecture().contractConfigured()
                ? report.architectureStatus() : "NOT CONFIGURED");
        row(out, "Analysis Mode", report.plan().repository().architecture().analysisMode());

        section(out, "Findings");
        if (report.findings().isEmpty()) {
            out.append("None\n");
        } else {
            for (VerificationFinding finding : report.findings()) {
                out.append(finding.severity()).append(" [").append(finding.category()).append("] ")
                        .append(finding.code()).append(' ').append(finding.path()).append(" — ")
                        .append(finding.message()).append('\n');
            }
        }

        section(out, "Limitations");
        report.limitations().forEach(limitation -> out.append("- ").append(limitation).append('\n'));

        section(out, "Decision");
        out.append("CHECK ").append(report.overallStatus()).append('\n');
        return out.toString();
    }

    private void section(StringBuilder out, String title) {
        out.append('\n').append(title).append('\n').append("----------------------------------\n");
    }

    private void row(StringBuilder out, String label, Object value) {
        out.append(String.format(Locale.ROOT, "%-24s %s%n", label, value));
    }

    private String shortHead(String head) {
        return head != null && head.length() > 12 ? head.substring(0, 12) : head;
    }
}
