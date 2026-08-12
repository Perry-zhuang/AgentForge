package io.agentforge.cli;

import io.agentforge.application.BaselineService;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(name = "baseline", description = "Capture repository state or compare it with a saved baseline.")
public class BaselineCommand implements Callable<Integer> {
    private final BaselineService baselineService;
    private final BaselineRenderer renderer;
    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "ACTION", description = "Action: capture or compare.")
    private String action;

    @Parameters(index = "1", arity = "0..1", defaultValue = ".", paramLabel = "PATH",
            description = "Repository path; defaults to the current directory.")
    private Path path;

    @Option(names = "--output", paramLabel = "FILE", description = "Write a captured baseline JSON to FILE.")
    private Path output;

    @Option(names = "--baseline", paramLabel = "FILE", description = "Compare against baseline JSON FILE.")
    private Path baseline;

    @Option(names = "--force", description = "Overwrite an existing --output file.")
    private boolean force;

    public BaselineCommand(BaselineService baselineService, BaselineRenderer renderer) {
        this.baselineService = baselineService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            String normalized = action.toLowerCase(Locale.ROOT);
            if ("capture".equals(normalized)) return capture();
            if ("compare".equals(normalized)) return compare();
            throw new IllegalArgumentException("Unknown baseline action: " + action + "; expected capture or compare");
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }

    private int capture() {
        if (output == null) throw new IllegalArgumentException("baseline capture requires --output");
        if (baseline != null) throw new IllegalArgumentException("--baseline is only valid with baseline compare");
        spec.commandLine().getOut().print(renderer.capture(baselineService.capture(path, output, force)));
        spec.commandLine().getOut().flush();
        return 0;
    }

    private int compare() {
        if (baseline == null) throw new IllegalArgumentException("baseline compare requires --baseline");
        if (output != null) throw new IllegalArgumentException("--output is only valid with baseline capture");
        if (force) throw new IllegalArgumentException("--force is only valid with baseline capture");
        spec.commandLine().getOut().print(renderer.compare(baselineService.compare(path, baseline)));
        spec.commandLine().getOut().flush();
        return 0;
    }
}
