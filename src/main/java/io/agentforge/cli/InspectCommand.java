package io.agentforge.cli;

import io.agentforge.application.InspectService;
import io.agentforge.model.TaskInspectionRequest;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "inspect", description = "Find source files relevant to a development task.")
public class InspectCommand implements Callable<Integer> {
    private final InspectService inspectService;
    private final TextReportRenderer renderer;
    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..2", paramLabel = "[PATH] TASK",
            description = "Quoted TASK, optionally preceded by repository PATH.")
    private String[] arguments;

    @Option(names = "--limit", defaultValue = "10", paramLabel = "N",
            description = "Maximum relevant files to show (1-15, default: ${DEFAULT-VALUE}).")
    private int limit;

    public InspectCommand(InspectService inspectService, TextReportRenderer renderer) {
        this.inspectService = inspectService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            Path path = arguments.length == 1 ? Path.of(".") : Path.of(arguments[0]);
            String task = arguments.length == 1 ? arguments[0] : arguments[1];
            TaskInspectionRequest request = new TaskInspectionRequest(path, task, limit);
            spec.commandLine().getOut().print(renderer.inspect(inspectService.inspect(request)));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
