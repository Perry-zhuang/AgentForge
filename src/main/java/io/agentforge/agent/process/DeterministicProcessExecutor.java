package io.agentforge.agent.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one direct child process while continuously and independently draining both output streams. */
public final class DeterministicProcessExecutor {
    private static final Duration GRACEFUL_TERMINATION = Duration.ofMillis(250);
    private static final Duration FORCED_TERMINATION = Duration.ofSeconds(2);

    public ProcessResult execute(ProcessRequest request) {
        if (request == null) throw new IllegalArgumentException("Process request is required");
        ProcessBuilder builder = new ProcessBuilder(request.command())
                .directory(request.workingDirectory().toFile());
        builder.environment().putAll(request.environmentOverrides());

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return new ProcessResult(null, "", "", false, false, true,
                    "Unable to start process: " + message(exception));
        }

        ExecutorService ioExecutor = Executors.newFixedThreadPool(3, new IoThreadFactory());
        CompletableFuture<Capture> stdout = CompletableFuture.supplyAsync(
                () -> drain(process.getInputStream(), "stdout"), ioExecutor);
        CompletableFuture<Capture> stderr = CompletableFuture.supplyAsync(
                () -> drain(process.getErrorStream(), "stderr"), ioExecutor);
        CompletableFuture<String> stdin = CompletableFuture.supplyAsync(
                () -> deliver(process.getOutputStream(), request.stdin()), ioExecutor);

        boolean timedOut = false;
        boolean interrupted = false;
        String terminationFailure = "";
        try {
            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                terminationFailure = terminate(process);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            terminationFailure = terminate(process);
        }

        if (process.isAlive()) {
            closeProcessStreams(process);
        }
        Capture capturedOut = stdout.join();
        Capture capturedErr = stderr.join();
        String inputFailure = stdin.join();
        Integer exitCode = process.isAlive() ? null : process.exitValue();
        String failure = failures(terminationFailure, capturedOut.failure(), capturedErr.failure(), inputFailure);

        ioExecutor.shutdownNow();
        if (process.isAlive()) process.destroyForcibly();
        if (interrupted) Thread.currentThread().interrupt();
        return new ProcessResult(exitCode, capturedOut.text(), capturedErr.text(), timedOut, interrupted, false,
                failure);
    }

    private String terminate(Process process) {
        process.destroy();
        if (exitedWithin(process, GRACEFUL_TERMINATION)) return "";
        process.destroyForcibly();
        if (exitedWithin(process, FORCED_TERMINATION)) return "";
        closeProcessStreams(process);
        return "Direct child process did not terminate after forced termination";
    }

    private boolean exitedWithin(Process process, Duration timeout) {
        return process.onExit().completeOnTimeout(null, timeout.toMillis(), TimeUnit.MILLISECONDS).join() != null;
    }

    private Capture drain(InputStream stream, String name) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (stream) {
            stream.transferTo(captured);
            return new Capture(captured.toString(StandardCharsets.UTF_8), "");
        } catch (IOException exception) {
            return new Capture(captured.toString(StandardCharsets.UTF_8),
                    "Unable to read process " + name + ": " + message(exception));
        }
    }

    private String deliver(OutputStream stream, String input) {
        try (stream) {
            stream.write(input.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            return "";
        } catch (IOException exception) {
            return "Unable to deliver process stdin: " + message(exception);
        }
    }

    private void closeProcessStreams(Process process) {
        close(process.getOutputStream());
        close(process.getInputStream());
        close(process.getErrorStream());
    }

    private void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The process result reports lifecycle state; cleanup failures add no new process output fact.
        }
    }

    private String failures(String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "; " + right).orElse("");
    }

    private String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record Capture(String text, String failure) {}

    private static final class IoThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "agent-process-io-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public record ProcessRequest(
            List<String> command,
            Path workingDirectory,
            String stdin,
            Duration timeout,
            Map<String, String> environmentOverrides
    ) {
        public ProcessRequest {
            if (command == null || command.isEmpty() || command.stream().anyMatch(
                    value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("Process command must not be empty");
            }
            command = List.copyOf(command);
            workingDirectory = canonicalDirectory(workingDirectory);
            if (stdin == null) throw new IllegalArgumentException("Process stdin is required");
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
                throw new IllegalArgumentException("Process timeout must be at least one millisecond");
            }
            environmentOverrides = immutableEnvironment(environmentOverrides);
        }

        private static Path canonicalDirectory(Path directory) {
            if (directory == null) throw new IllegalArgumentException("Process working directory is required");
            try {
                Path canonical = directory.toRealPath();
                if (!Files.isDirectory(canonical)) {
                    throw new IllegalArgumentException("Process working directory is not a directory: " + canonical);
                }
                return canonical;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to resolve process working directory: " + directory,
                        exception);
            }
        }

        private static Map<String, String> immutableEnvironment(Map<String, String> overrides) {
            if (overrides == null) throw new IllegalArgumentException("Process environment overrides are required");
            TreeMap<String, String> copy = new TreeMap<>();
            overrides.forEach((name, value) -> {
                if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Invalid process environment name: " + name);
                }
                if (value == null || value.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Invalid process environment value for: " + name);
                }
                copy.put(name, value);
            });
            return Collections.unmodifiableMap(copy);
        }
    }

    public record ProcessResult(
            Integer exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean interrupted,
            boolean startFailed,
            String failureMessage
    ) {
        public ProcessResult {
            if (stdout == null || stderr == null || failureMessage == null) {
                throw new IllegalArgumentException("Process output facts are required");
            }
            if ((timedOut && interrupted) || (startFailed && (timedOut || interrupted))) {
                throw new IllegalArgumentException("Process lifecycle facts are inconsistent");
            }
            if (startFailed && exitCode != null) {
                throw new IllegalArgumentException("A process that did not start cannot have an exit code");
            }
        }
    }
}
