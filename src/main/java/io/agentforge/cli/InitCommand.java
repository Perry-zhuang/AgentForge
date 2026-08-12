package io.agentforge.cli;

import io.agentforge.application.InitService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "init", description = "Initialize AgentForge repository governance files.")
public class InitCommand implements Callable<Integer> {
    private final InitService initService;
    private final TextReportRenderer renderer;
    @Spec
    private CommandSpec spec;

    public InitCommand(InitService initService, TextReportRenderer renderer) {
        this.initService = initService;
        this.renderer = renderer;
    }

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "PATH")
    private Path path;

    @Override
    public Integer call() {
        try {
            spec.commandLine().getOut().print(renderer.init(initService.initialize(path)));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }
}
