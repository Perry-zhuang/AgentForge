package io.agentforge.cli;

import io.agentforge.application.AuditService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "audit", description = "Audit repository readiness for coding agents.")
public class AuditCommand implements Callable<Integer> {
    private final AuditService auditService;
    private final TextReportRenderer renderer;
    @Spec
    private CommandSpec spec;

    public AuditCommand(AuditService auditService, TextReportRenderer renderer) {
        this.auditService = auditService;
        this.renderer = renderer;
    }

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "PATH")
    private Path path;

    @Override
    public Integer call() {
        try {
            spec.commandLine().getOut().print(renderer.audit(auditService.audit(path)));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
