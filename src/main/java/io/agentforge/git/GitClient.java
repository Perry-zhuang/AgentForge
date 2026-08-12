package io.agentforge.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GitClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public Result run(Path directory, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toAbsolutePath().normalize().toString());
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return running.getInputStream().readAllBytes();
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            boolean completed = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new Result(124, "", "Git command timed out after " + TIMEOUT.toSeconds() + " seconds");
            }
            String output = new String(outputFuture.join(), StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output, process.exitValue() == 0 ? "" : output.strip());
        } catch (IOException exception) {
            return new Result(127, "", "Unable to execute Git: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(130, "", "Git command interrupted");
        } catch (CompletionException exception) {
            return new Result(127, "", "Unable to read Git output: " + exception.getCause().getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    public Result run(Path directory, String... arguments) {
        return run(directory, List.of(arguments));
    }

    public Result statusPorcelain(Path gitRoot, String scope) {
        return run(gitRoot, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", scope);
    }

    public Result diff(Path gitRoot, boolean cached, String scope) {
        List<String> arguments = new ArrayList<>(List.of("diff"));
        if (cached) arguments.add("--cached");
        arguments.add("--");
        arguments.add(scope);
        return run(gitRoot, arguments);
    }

    public Result diffNameOnly(Path gitRoot, boolean cached, String scope) {
        List<String> arguments = new ArrayList<>(List.of("diff", "--name-only", "-z"));
        if (cached) arguments.add("--cached");
        arguments.add("--");
        arguments.add(scope);
        return run(gitRoot, arguments);
    }

    public record Result(int exitCode, String output, String error) {
        public boolean succeeded() {
            return exitCode == 0;
        }

        public String trimmedOutput() {
            return output.strip();
        }
    }
}
