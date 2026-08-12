package io.agentforge.cli;

import io.agentforge.application.CheckService;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationRequest;
import io.agentforge.model.VerificationStatus;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(name = "check", description = "Verify current or execution Git changes against a task's engineering boundary.")
public class CheckCommand implements Callable<Integer> {
    private final CheckService checkService;
    private final CheckRenderer renderer;
    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..2", paramLabel = "[PATH] TASK",
            description = "Quoted TASK, optionally preceded by repository PATH.")
    private String[] arguments;

    @Option(names = "--baseline", paramLabel = "FILE",
            description = "Verify only changes introduced after baseline JSON FILE.")
    private Path baseline;

    public CheckCommand(CheckService checkService, CheckRenderer renderer) {
        this.checkService = checkService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            Path path = arguments.length == 1 ? Path.of(".") : Path.of(arguments[0]);
            String task = arguments.length == 1 ? arguments[0] : arguments[1];
            VerificationReport report = checkService.check(new VerificationRequest(path, task, baseline));
            spec.commandLine().getOut().print(renderer.render(report));
            spec.commandLine().getOut().flush();
            return report.overallStatus() == VerificationStatus.FAIL ? 1 : 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
