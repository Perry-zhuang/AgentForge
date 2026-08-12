package io.agentforge.cli;

import io.agentforge.application.PlanService;
import io.agentforge.model.EngineeringPlanRequest;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(name = "plan", description = "Generate a deterministic engineering plan from inspect results.")
public class PlanCommand implements Callable<Integer> {
    private final PlanService planService;
    private final TextReportRenderer renderer;
    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..2", paramLabel = "[PATH] TASK",
            description = "Quoted TASK, optionally preceded by repository PATH.")
    private String[] arguments;

    public PlanCommand(PlanService planService, TextReportRenderer renderer) {
        this.planService = planService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            Path path = arguments.length == 1 ? Path.of(".") : Path.of(arguments[0]);
            String task = arguments.length == 1 ? arguments[0] : arguments[1];
            spec.commandLine().getOut().print(renderer.plan(
                    planService.plan(new EngineeringPlanRequest(path, task))));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
