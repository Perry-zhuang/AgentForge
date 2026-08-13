package io.agentforge.runtrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.application.AgentRunException;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.VerificationFinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class LocalRunTraceService implements RunTraceService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path archiveRoot;
    private final Clock clock;
    private final RunIdGenerator runIdGenerator;
    private final RunMarkdownRenderer markdownRenderer;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Map<Path, Context> contexts = new ConcurrentHashMap<>();

    @Autowired
    public LocalRunTraceService(RunMarkdownRenderer markdownRenderer) {
        this(Path.of(System.getProperty("user.home"), ".agentforge", "runs"), Clock.systemUTC(),
                defaultGenerator(), markdownRenderer);
    }

    public LocalRunTraceService(Path archiveRoot, Clock clock, RunIdGenerator runIdGenerator,
                                RunMarkdownRenderer markdownRenderer) {
        this.archiveRoot = archiveRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.runIdGenerator = runIdGenerator;
        this.markdownRenderer = markdownRenderer;
    }

    @Override
    public RunTraceReference prepare(AgentRunRequest request, EngineeringPlan plan, String renderedPrompt,
                                     ExecutionBaseline baseline, CodingAgentIdentity agent) {
        try {
            Path repository = plan.repository().repository().path().toRealPath();
            Path safeRoot = safeFuturePath(archiveRoot);
            rejectInside(repository, safeRoot, "Run archive root must be outside the target repository.");
            Path output = request.outputFile() == null ? null : validateOutput(repository, request.outputFile());
            Instant started = clock.instant();
            String runId = runIdGenerator.generate(started);
            Path directory = safeRoot.resolve(DAY.format(started)).resolve(runId);
            createPrivateDirectories(directory);
            RunTraceReference reference = new RunTraceReference(runId, directory,
                    directory.resolve("report.md"), output);
            Context context = new Context(request, plan, baseline, agent, renderedPrompt, started, output);
            contexts.put(directory, context);
            writeStructured(directory.resolve("question.md"), question(context));
            writeRaw(directory.resolve("prompt.md"), renderedPrompt);
            writeStructured(directory.resolve("run.json"), manifest(reference, context, null, null,
                    null, "IN_PROGRESS", null, null));
            return reference;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare run archive: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void persistAgentResult(RunTraceReference trace, AgentExecutionResult result) {
        Context context = requireContext(trace);
        writeRaw(trace.archiveDirectory().resolve("agent-output.md"), result.stdout());
        writeRaw(trace.archiveDirectory().resolve("agent-stderr.log"), result.stderr());
        context.agentResult = result;
        writeStructured(trace.archiveDirectory().resolve("run.json"), manifest(trace, context, result, null,
                null, "IN_PROGRESS", null, null));
    }

    @Override
    public RunTraceReference complete(RunTraceReference trace, AgentRunResult result) {
        Context context = requireContext(trace);
        String report = markdownRenderer.render(trace, result, sha(context.renderedPrompt));
        writeStructured(trace.reportFile(), report);
        RunTraceReference completed = trace;
        if (context.output != null) {
            writeStructured(context.output, report);
            completed = trace.withExport(context.output);
        }
        writeStructured(trace.archiveDirectory().resolve("run.json"), manifest(completed, context,
                result.agentExecutionResult(), result.executionChangeReport(), result,
                "COMPLETE", null, null));
        contexts.remove(trace.archiveDirectory());
        return completed;
    }

    @Override
    public void fail(RunTraceReference trace, AgentRunException.Phase phase, String message,
                     AgentExecutionResult agentResult, ExecutionChangeReport executionDelta) {
        if (trace == null) return;
        Context context = contexts.get(trace.archiveDirectory());
        if (context == null) return;
        try {
            if (agentResult != null && context.agentResult == null) persistAgentResult(trace, agentResult);
            String report = partialReport(trace, context, phase, message, agentResult, executionDelta);
            writeStructured(trace.reportFile(), report);
            writeStructured(trace.archiveDirectory().resolve("run.json"), manifest(trace, context,
                    agentResult, executionDelta, null, "INCOMPLETE", phase.name(), message));
        } finally {
            contexts.remove(trace.archiveDirectory());
        }
    }

    private Map<String, Object> manifest(RunTraceReference trace, Context context, AgentExecutionResult execution,
                                         ExecutionChangeReport delta, AgentRunResult result, String lifecycle,
                                         String failureStage, String failureMessage) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("runId", trace.runId());
        root.put("startedAt", context.started.toString());
        root.put("completedAt", lifecycle.equals("IN_PROGRESS") ? null : clock.instant().toString());
        root.put("repository", ordered("root", context.baseline.repositorySnapshot().repositoryRoot(),
                "branch", context.baseline.repositorySnapshot().branch(), "head", context.baseline.repositorySnapshot().head()));
        root.put("task", ordered("text", context.request.task(), "sha256", sha(context.request.task()),
                "type", context.plan.taskType().name()));
        var classification = context.plan.taskClassification();
        root.put("taskClassification", ordered(
                "detectedType", classification.detectedType().name(),
                "effectiveType", classification.effectiveType().name(),
                "typeConfidence", classification.typeConfidence().name(),
                "mutationMode", classification.mutationMode().name(),
                "securityImpact", classification.securityImpact().name(),
                "classificationSource", classification.source().name(),
                "signals", classification.signals().stream().map(Enum::name).toList()));
        root.put("agent", ordered("type", context.agent.type().name(), "id", context.agent.id()));
        root.put("prompt", ordered("file", "prompt.md", "sha256", sha(context.renderedPrompt)));
        root.put("execution", execution == null ? null : ordered("status", execution.status().name(),
                "exitCode", execution.processExitCode(), "stdoutFile", "agent-output.md",
                "stdoutSha256", sha(execution.stdout()), "stderrFile", "agent-stderr.log",
                "stderrSha256", sha(execution.stderr())));
        root.put("delta", delta == null ? null : ordered("files", delta.changes().size(), "changes",
                delta.changes().stream().map(change -> ordered("type", change.type().name(), "path", change.path(),
                        "oldPath", change.oldPath(), "contentChanged", change.contentChanged(),
                        "gitStateChanged", change.gitStateChanged())).toList()));
        root.put("verification", result == null ? null : ordered("overall", result.verificationReport().overallStatus().name(),
                "scope", result.verificationReport().scopeStatus().name(), "policy",
                result.verificationReport().policyStatus().name(), "taskType",
                result.verificationReport().taskTypeStatus().name(), "architecture",
                result.verificationReport().executionBoundary().contractConfigured()
                        ? result.verificationReport().architectureStatus().name() : "NOT_CONFIGURED",
                "findings", result.verificationReport().findings().stream().map(this::finding).toList()));
        root.put("decision", result == null ? null : result.status().name());
        root.put("lifecycle", ordered("status", lifecycle, "failureStage", failureStage,
                "failureMessage", failureMessage));
        return root;
    }

    private Map<String, Object> finding(VerificationFinding finding) {
        return ordered("category", finding.category().name(), "severity", finding.severity().name(),
                "code", finding.code(), "path", finding.path(), "message", finding.message());
    }

    private String question(Context context) {
        return "# AgentForge Question\n\n## Repository\n\n`" + context.plan.repository().repository().path()
                + "`\n\n## Task\n\n" + context.request.task() + "\n\n## Task Type\n\n"
                + context.plan.taskType() + "\n\n## Agent\n\n" + context.agent.type() + "\n";
    }

    private String partialReport(RunTraceReference trace, Context context, AgentRunException.Phase phase,
                                 String message, AgentExecutionResult agent, ExecutionChangeReport delta) {
        return "# AgentForge Run Report\n\n## Run\n\nRun ID: `" + trace.runId() + "`\n\n"
                + "## Question\n\n" + context.request.task() + "\n\n## Agent Execution\n\nStatus: `"
                + (agent == null ? "NOT_STARTED" : agent.status()) + "`\n\n## Execution Delta\n\nFiles: "
                + (delta == null ? "INCOMPLETE" : delta.changes().size()) + "\n\n## Decision\n\n"
                + "`AGENT RUN INCOMPLETE`\n\nFailure Stage: `" + phase + "`\n\nFailure: " + message + "\n";
    }

    private Path validateOutput(Path repository, Path requested) throws IOException {
        Path candidate = requested.toAbsolutePath().normalize();
        if (!candidate.getFileName().toString().toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("Output file must use the .md extension.");
        }
        if (Files.exists(candidate)) throw new IllegalArgumentException("Output file already exists: " + candidate);
        Path resolved = safeFuturePath(candidate);
        rejectInside(repository, resolved, "Output file must be outside the target repository.");
        return resolved;
    }

    private Path safeFuturePath(Path path) throws IOException {
        Path candidate = path.toAbsolutePath().normalize();
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("Path has no existing ancestor: " + candidate);
        Path real = existing.toRealPath();
        return real.resolve(existing.relativize(candidate)).normalize();
    }

    private void rejectInside(Path repository, Path candidate, String message) {
        if (candidate.equals(repository) || candidate.startsWith(repository)) throw new IllegalArgumentException(message);
    }

    private void createPrivateDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path agentforgeHome = archiveRoot.getParent();
        if (agentforgeHome != null && agentforgeHome.getFileName() != null
                && agentforgeHome.getFileName().toString().equals(".agentforge")) {
            setPermissions(agentforgeHome, DIRECTORY_PERMISSIONS);
        }
        Path cursor = directory;
        while (cursor != null && cursor.startsWith(archiveRoot)) {
            setPermissions(cursor, DIRECTORY_PERMISSIONS);
            if (cursor.equals(archiveRoot)) break;
            cursor = cursor.getParent();
        }
    }

    private void writeRaw(Path target, String value) {
        atomicWrite(target, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeStructured(Path target, Object value) {
        try {
            String text = value instanceof String string ? string : mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value).replace("\r\n", "\n") + "\n";
            atomicWrite(target, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize run trace: " + exception.getMessage(), exception);
        }
    }

    private void atomicWrite(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".agentforge-trace-", ".tmp");
            Files.write(temporary, bytes);
            setPermissions(temporary, FILE_PERMISSIONS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(target, FILE_PERMISSIONS);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write run trace file " + target + ": "
                    + exception.getMessage(), exception);
        }
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Cross-platform best effort.
        }
    }

    private Context requireContext(RunTraceReference trace) {
        Context context = contexts.get(trace.archiveDirectory());
        if (context == null) throw new IllegalStateException("Run trace is not active: " + trace.runId());
        return context;
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static RunIdGenerator defaultGenerator() {
        return instant -> ID_TIME.format(instant) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static final class Context {
        private final AgentRunRequest request;
        private final EngineeringPlan plan;
        private final ExecutionBaseline baseline;
        private final CodingAgentIdentity agent;
        private final String renderedPrompt;
        private final Instant started;
        private final Path output;
        private AgentExecutionResult agentResult;

        private Context(AgentRunRequest request, EngineeringPlan plan, ExecutionBaseline baseline,
                        CodingAgentIdentity agent, String renderedPrompt, Instant started, Path output) {
            this.request = request;
            this.plan = plan;
            this.baseline = baseline;
            this.agent = agent;
            this.renderedPrompt = renderedPrompt;
            this.started = started;
            this.output = output;
        }
    }
}
