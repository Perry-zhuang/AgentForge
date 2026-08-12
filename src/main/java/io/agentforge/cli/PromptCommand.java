package io.agentforge.cli;

import io.agentforge.application.PromptService;
import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.EngineeringPromptRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(name = "prompt", description = "Generate a deterministic engineering prompt from an engineering plan.")
public class PromptCommand implements Callable<Integer> {
    private final PromptService promptService;
    private final PromptRenderer renderer;
    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..2", paramLabel = "[PATH] TASK",
            description = "Quoted TASK, optionally preceded by repository PATH.")
    private String[] arguments;

    @Option(names = "--output", paramLabel = "FILE",
            description = "Write the UTF-8 prompt to FILE instead of standard output.")
    private Path output;

    @Option(names = "--force", description = "Overwrite an existing --output file.")
    private boolean force;

    public PromptCommand(PromptService promptService, PromptRenderer renderer) {
        this.promptService = promptService;
        this.renderer = renderer;
    }

    @Override
    public Integer call() {
        try {
            if (force && output == null) {
                throw new IllegalArgumentException("--force requires --output");
            }
            Path repository = arguments.length == 1 ? Path.of(".") : Path.of(arguments[0]);
            String task = arguments.length == 1 ? arguments[0] : arguments[1];
            Path destination = output == null ? null : validateOutput(output);
            EngineeringPrompt prompt = promptService.prompt(new EngineeringPromptRequest(repository, task));
            String rendered = renderer.render(prompt);
            if (destination == null) {
                spec.commandLine().getOut().print(rendered);
                spec.commandLine().getOut().flush();
            } else {
                if (insideRepository(destination, prompt.repository().repository().path())) {
                    spec.commandLine().getErr().println(
                            "WARNING: Prompt output is being written inside the target repository.");
                    spec.commandLine().getErr().flush();
                }
                write(destination, rendered);
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            spec.commandLine().getErr().println("ERROR " + exception.getMessage());
            spec.commandLine().getErr().flush();
            return 2;
        }
    }

    private Path validateOutput(Path requested) {
        Path destination = requested.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null || !Files.exists(parent)) {
            throw new IllegalArgumentException("Output parent directory does not exist: " + parent);
        }
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Output parent is not a directory: " + parent);
        }
        if (Files.isDirectory(destination)) {
            throw new IllegalArgumentException("Output path is a directory: " + destination);
        }
        if (Files.exists(destination) && !force) {
            throw new IllegalArgumentException("Output file already exists; use --force to overwrite: " + destination);
        }
        return destination;
    }

    private boolean insideRepository(Path outputPath, Path repositoryPath) throws IOException {
        Path repository = repositoryPath.toRealPath();
        Path lexicalOutput = outputPath.toAbsolutePath().normalize();
        Path realOutput = Files.exists(outputPath)
                ? outputPath.toRealPath()
                : outputPath.getParent().toRealPath().resolve(outputPath.getFileName()).normalize();
        return lexicalOutput.startsWith(repository) || realOutput.startsWith(repository);
    }

    private void write(Path destination, String content) throws IOException {
        if (force) {
            Files.writeString(destination, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } else {
            Files.writeString(destination, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }
}
