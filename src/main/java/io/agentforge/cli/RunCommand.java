package io.agentforge.cli;

import io.agentforge.agent.CodingAgentType;
import io.agentforge.application.AgentExecutionService;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import io.agentforge.application.AgentRunException;

/** Thin CLI entry point over the frozen execution-orchestration lifecycle. */
@Component
@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Execute one coding agent and verify its attributed repository changes.")
public class RunCommand implements Callable<Integer> {
    static final int DEFAULT_TIMEOUT_SECONDS = 900;

    private final AgentExecutionService executionService;
    private final RunRenderer renderer;

    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..2", paramLabel = "[PATH] TASK",
            description = "Quoted TASK, optionally preceded by repository PATH.")
    private String[] arguments;

    @Option(names = "--agent", required = true, paramLabel = "TYPE",
            description = "Coding agent type (currently: codex).")
    private String agent;

    @Option(names = "--timeout-seconds", defaultValue = "" + DEFAULT_TIMEOUT_SECONDS, paramLabel = "N",
            description = "Agent execution timeout in whole seconds (default: ${DEFAULT-VALUE}).")
    private int timeoutSeconds;

    @Option(names = "--output", paramLabel = "FILE",
            description = "Export the archived human-readable report to a new .md file.")
    private Path output;

    public RunCommand(AgentExecutionService executionService, RunRenderer renderer) {
        this.executionService = executionService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            if (timeoutSeconds < 1) {
                throw new IllegalArgumentException("--timeout-seconds must be at least 1");
            }
            Path path = arguments.length == 1 ? Path.of(".") : Path.of(arguments[0]);
            String task = arguments.length == 1 ? arguments[0] : arguments[1];
            AgentRunRequest request = new AgentRunRequest(path, task, CodingAgentType.parse(agent),
                    Duration.ofSeconds(timeoutSeconds), Map.of(), output);
            AgentRunResult result = executionService.execute(request);
            spec.commandLine().getOut().print(renderer.render(result));
            spec.commandLine().getOut().flush();
            return successful(result) ? 0 : 1;
        } catch (AgentRunException exception) {
            if (exception.runTrace().isPresent()) {
                spec.commandLine().getOut().print(renderer.renderIncomplete(exception));
                spec.commandLine().getOut().flush();
            } else {
                spec.commandLine().getErr().println("ERROR " + exception.getMessage());
                spec.commandLine().getErr().flush();
            }
            return 2;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }

    private boolean successful(AgentRunResult result) {
        return switch (result.status()) {
            case PASS, WARNING -> true;
            default -> false;
        };
    }
}
