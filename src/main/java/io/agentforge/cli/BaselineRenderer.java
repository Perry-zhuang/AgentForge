package io.agentforge.cli;

import io.agentforge.application.BaselineService.CaptureResult;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.ExecutionChangeReport.ExecutionChange;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class BaselineRenderer {
    public String capture(CaptureResult result) {
        StringBuilder out = new StringBuilder("AgentForge Baseline\n");
        section(out, "Repository Snapshot");
        row(out, "Repository", result.snapshot().repositoryRoot());
        row(out, "Branch", result.snapshot().branch());
        row(out, "HEAD", result.snapshot().head());
        row(out, "Schema Version", result.snapshot().schemaVersion());
        row(out, "Files", result.snapshot().files().size());
        row(out, "Tracked", result.snapshot().files().stream().filter(file -> file.tracked()).count());
        row(out, "Untracked", result.snapshot().files().stream().filter(file -> !file.tracked()).count());
        row(out, "Ignored Files", "EXCLUDED");
        row(out, "Output", result.destination());
        section(out, "Decision");
        out.append("BASELINE CAPTURED\n");
        return out.toString();
    }

    public String compare(ExecutionChangeReport report) {
        StringBuilder out = new StringBuilder("AgentForge Execution Delta\n");
        section(out, "Repository");
        row(out, "Path", report.repositoryRoot());

        section(out, "Git Facts");
        row(out, "Baseline Branch", report.baselineBranch());
        row(out, "Current Branch", report.currentBranch());
        row(out, "Branch Changed", report.branchChanged());
        row(out, "Baseline HEAD", report.baselineHead());
        row(out, "Current HEAD", report.currentHead());
        row(out, "HEAD Changed", report.headChanged());

        section(out, "Execution Changes");
        row(out, "Files", report.changes().size());
        if (report.changes().isEmpty()) {
            out.append("None\n");
        } else {
            report.changes().forEach(change -> out.append(render(change)).append('\n'));
        }

        section(out, "Decision");
        out.append("EXECUTION DELTA ").append(report.hasExecutionChanges() ? "CHANGED" : "NONE").append('\n');
        return out.toString();
    }

    private String render(ExecutionChange change) {
        String path = change.type() == ExecutionChangeReport.ChangeType.RENAMED
                ? change.oldPath() + " -> " + change.path() : change.path();
        StringBuilder facts = new StringBuilder();
        if (change.contentChanged()) facts.append("CONTENT_CHANGED");
        if (change.gitStateChanged()) {
            if (!facts.isEmpty()) facts.append(", ");
            facts.append("GIT_STATE_CHANGED");
        }
        return change.type() + " " + path + (facts.isEmpty() ? "" : " [" + facts + "]");
    }

    private void section(StringBuilder out, String title) {
        out.append('\n').append(title).append('\n').append("----------------------------------\n");
    }

    private void row(StringBuilder out, String label, Object value) {
        out.append(String.format(Locale.ROOT, "%-24s %s%n", label, value));
    }
}
