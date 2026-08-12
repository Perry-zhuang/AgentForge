package io.agentforge.cli;

import io.agentforge.application.DiffAuditService;
import io.agentforge.model.DiffAuditReport;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "diff", description = "Audit current Git changes against repository policies.")
public class DiffCommand implements Callable<Integer> {
    private final DiffAuditService diffAuditService;
    private final TextReportRenderer renderer;
    @Spec
    private CommandSpec spec;

    public DiffCommand(DiffAuditService diffAuditService, TextReportRenderer renderer) {
        this.diffAuditService = diffAuditService;
        this.renderer = renderer;
    }

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "PATH")
    private Path path;

    @Override
    public Integer call() {
        try {
            DiffAuditReport report = diffAuditService.audit(path);
            spec.commandLine().getOut().print(renderer.diff(report));
            spec.commandLine().getOut().flush();
            return report.policyReport().failed() ? 1 : 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
