package io.agentforge.runtrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.application.AgentRunException;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRunTraceServiceTest {
    @TempDir Path temp;

    @Test
    void archivesExactQuestionPromptStreamsHashesCompleteManifestReportAndExport() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repo")).toRealPath();
        Path export = temp.resolve("reports/result.md");
        Fixture fixture = fixture(repository, export);
        String task = "  排查 CMP 用户\n不要改源码  ";
        String prompt = "  # Prompt\r\n中文  \n";
        AgentRunRequest request = new AgentRunRequest(repository, task, CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of(), export);

        RunTraceReference trace = fixture.service.prepare(request, fixture.plan, prompt, fixture.baseline,
                fixture.identity);
        AgentExecutionResult execution = new AgentExecutionResult(fixture.identity, 0,
                "  ## 结论\n\n- 不创建\r\n", "runtime diagnostic\r\n", false, false,
                AgentExecutionStatus.SUCCESS, "");
        fixture.service.persistAgentResult(trace, execution);
        AgentRunResult result = fixture.result(task, execution, trace);
        RunTraceReference completed = fixture.service.complete(trace, result);

        assertThat(Files.readString(trace.archiveDirectory().resolve("question.md"))).contains(task);
        assertThat(Files.readAllBytes(trace.archiveDirectory().resolve("prompt.md")))
                .isEqualTo(prompt.getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(trace.archiveDirectory().resolve("agent-output.md")))
                .isEqualTo(execution.stdout().getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(trace.archiveDirectory().resolve("agent-stderr.log")))
                .isEqualTo(execution.stderr().getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(trace.reportFile())).contains("## Agent Output", "## Decision", "不创建");
        assertThat(Files.readAllBytes(export)).isEqualTo(Files.readAllBytes(trace.reportFile()));
        assertThat(completed.exportedReport()).isEqualTo(export.getParent().toRealPath().resolve("result.md"));

        JsonNode json = new ObjectMapper().readTree(trace.archiveDirectory().resolve("run.json").toFile());
        assertThat(json.path("schemaVersion").asInt()).isOne();
        assertThat(json.path("taskClassification").path("detectedType").asText()).isEqualTo("ANALYSIS");
        assertThat(json.path("taskClassification").path("effectiveType").asText()).isEqualTo("ANALYSIS");
        assertThat(json.path("taskClassification").path("typeConfidence").asText()).isEqualTo("HIGH");
        assertThat(json.path("taskClassification").path("mutationMode").asText()).isEqualTo("READ_ONLY");
        assertThat(json.path("taskClassification").path("securityImpact").asText()).isEqualTo("NONE");
        assertThat(json.path("taskClassification").path("classificationSource").asText()).isEqualTo("DETECTED");
        assertThat(json.path("lifecycle").path("status").asText()).isEqualTo("COMPLETE");
        assertThat(json.path("task").path("text").asText()).isEqualTo(task);
        assertThat(json.path("task").path("sha256").asText()).isEqualTo(sha(task));
        assertThat(json.path("prompt").path("sha256").asText()).isEqualTo(sha(prompt));
        assertThat(json.path("execution").path("stdoutSha256").asText()).isEqualTo(sha(execution.stdout()));
        assertThat(json.path("execution").path("stderrSha256").asText()).isEqualTo(sha(execution.stderr()));
        assertThat(json.path("repository").path("branch").asText()).isEqualTo("main");
        assertThat(json.path("repository").path("head").asText()).isEqualTo("abc123");
        assertThat(json.path("delta").path("files").asInt()).isOne();
        assertThat(json.path("verification").path("overall").asText()).isEqualTo("PASS");
        assertThat(json.path("decision").asText()).isEqualTo("PASS");
    }

    @Test
    void persistsIncompleteLifecycleWithPartialAgentAndDeltaEvidence() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repo")).toRealPath();
        Fixture fixture = fixture(repository, null);
        AgentRunRequest request = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of());
        RunTraceReference trace = fixture.service.prepare(request, fixture.plan, "prompt", fixture.baseline,
                fixture.identity);
        AgentExecutionResult execution = new AgentExecutionResult(fixture.identity, 0, "answer", "diag",
                false, false, AgentExecutionStatus.SUCCESS, "");
        fixture.service.persistAgentResult(trace, execution);
        fixture.service.fail(trace, AgentRunException.Phase.VERIFICATION, "check unavailable", execution,
                fixture.delta);

        JsonNode json = new ObjectMapper().readTree(trace.archiveDirectory().resolve("run.json").toFile());
        assertThat(json.path("lifecycle").path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(json.path("lifecycle").path("failureStage").asText()).isEqualTo("VERIFICATION");
        assertThat(json.path("lifecycle").path("failureMessage").asText()).isEqualTo("check unavailable");
        assertThat(json.path("execution").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(json.path("delta").path("files").asInt()).isOne();
        assertThat(Files.readString(trace.reportFile())).contains("AGENT RUN INCOMPLETE", "VERIFICATION");
    }

    @Test
    void recordsDetectedAndEffectiveTypesForUserOverrideWithoutChangingSchema() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("override-repo")).toRealPath();
        Fixture fixture = fixture(repository, null);
        var detected = new io.agentforge.planning.TaskTypeClassifier()
                .classifyFacts("把用户这块处理一下 password", TaskType.FEATURE);
        when(fixture.plan.taskClassification()).thenReturn(detected);
        when(fixture.plan.taskType()).thenReturn(TaskType.FEATURE);
        AgentRunRequest request = new AgentRunRequest(repository, "把用户这块处理一下 password",
                CodingAgentType.CODEX, Duration.ofSeconds(5), Map.of(), null, TaskType.FEATURE);

        RunTraceReference trace = fixture.service.prepare(request, fixture.plan, "prompt", fixture.baseline,
                fixture.identity);

        JsonNode json = new ObjectMapper().readTree(trace.archiveDirectory().resolve("run.json").toFile());
        assertThat(json.path("schemaVersion").asInt()).isOne();
        assertThat(json.path("taskClassification").path("detectedType").asText()).isEqualTo("UNKNOWN");
        assertThat(json.path("taskClassification").path("effectiveType").asText()).isEqualTo("FEATURE");
        assertThat(json.path("taskClassification").path("classificationSource").asText())
                .isEqualTo("USER_OVERRIDE");
        assertThat(json.path("taskClassification").path("securityImpact").asText()).isEqualTo("HIGH");
    }

    @Test
    void rejectsArchiveAndOutputPathsInsideRepositoryIncludingCanonicalAndSymlinkPaths() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repo")).toRealPath();
        Fixture fixture = fixture(repository, null);
        AgentRunRequest request = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of());
        LocalRunTraceService inside = service(repository.resolve("trace"));
        assertThatThrownBy(() -> inside.prepare(request, fixture.plan, "prompt", fixture.baseline, fixture.identity))
                .hasMessageContaining("Run archive root must be outside");

        AgentRunRequest lexical = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of(), repository.resolve("../repo/report.md"));
        assertThatThrownBy(() -> fixture.service.prepare(lexical, fixture.plan, "prompt", fixture.baseline,
                fixture.identity)).hasMessageContaining("Output file must be outside");

        Path link = temp.resolve("repo-link");
        try {
            Files.createSymbolicLink(link, repository);
            AgentRunRequest symlink = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                    Duration.ofSeconds(5), Map.of(), link.resolve("report.md"));
            assertThatThrownBy(() -> fixture.service.prepare(symlink, fixture.plan, "prompt", fixture.baseline,
                    fixture.identity)).hasMessageContaining("Output file must be outside");
        } catch (UnsupportedOperationException ignored) {
            // Platform has no symlink support.
        }
    }

    @Test
    void rejectsExistingAndNonMarkdownExportsBeforeCreatingArchive() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repo")).toRealPath();
        Fixture fixture = fixture(repository, null);
        Path existing = Files.writeString(temp.resolve("exists.md"), "old");
        AgentRunRequest existingRequest = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of(), existing);
        assertThatThrownBy(() -> fixture.service.prepare(existingRequest, fixture.plan, "prompt", fixture.baseline,
                fixture.identity)).hasMessageContaining("already exists");
        AgentRunRequest text = new AgentRunRequest(repository, "分析", CodingAgentType.CODEX,
                Duration.ofSeconds(5), Map.of(), temp.resolve("report.txt"));
        assertThatThrownBy(() -> fixture.service.prepare(text, fixture.plan, "prompt", fixture.baseline,
                fixture.identity)).hasMessageContaining(".md extension");
    }

    private Fixture fixture(Path repository, Path output) {
        EngineeringPlan plan = mock(EngineeringPlan.class);
        RepositoryReport report = mock(RepositoryReport.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(plan.repository()).thenReturn(report);
        when(report.repository()).thenReturn(info);
        when(info.path()).thenReturn(repository);
        when(plan.taskType()).thenReturn(TaskType.ANALYSIS);
        when(plan.taskClassification()).thenReturn(io.agentforge.model.TaskClassification.legacy(
                TaskType.ANALYSIS, "ANALYSIS"));
        CodingAgentIdentity identity = new CodingAgentIdentity(CodingAgentType.CODEX, "codex", Set.of());
        RepositorySnapshot snapshot = new RepositorySnapshot(1, repository.toString(), "main", "abc123", List.of());
        EngineeringBoundarySnapshot boundary = mock(EngineeringBoundarySnapshot.class);
        ExecutionBaseline baseline = new ExecutionBaseline(2, snapshot, boundary);
        ExecutionChangeReport delta = new ExecutionChangeReport(repository.toString(), "main", "main", false,
                "abc123", "abc123", false, List.of(new ExecutionChangeReport.ExecutionChange(
                ExecutionChangeReport.ChangeType.MODIFIED, "src/A.java", null, true, true)));
        return new Fixture(service(temp.resolve("archive")), plan, baseline, identity, delta, boundary);
    }

    private LocalRunTraceService service(Path root) {
        return new LocalRunTraceService(root,
                Clock.fixed(Instant.parse("2026-08-13T08:05:12.123Z"), ZoneOffset.UTC),
                instant -> "20260813-080512.123-test0001", new RunMarkdownRenderer());
    }

    private String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(LocalRunTraceService service, EngineeringPlan plan, ExecutionBaseline baseline,
                           CodingAgentIdentity identity, ExecutionChangeReport delta,
                           EngineeringBoundarySnapshot boundary) {
        AgentRunResult result(String task, AgentExecutionResult execution, RunTraceReference trace) {
            VerificationReport verification = mock(VerificationReport.class);
            when(verification.executionBoundary()).thenReturn(boundary);
            when(boundary.primaryPaths()).thenReturn(List.of("src/A.java"));
            when(boundary.secondaryPaths()).thenReturn(List.of());
            when(boundary.referencePaths()).thenReturn(List.of());
            when(boundary.contractConfigured()).thenReturn(false);
            when(verification.overallStatus()).thenReturn(VerificationStatus.PASS);
            when(verification.scopeStatus()).thenReturn(VerificationStatus.PASS);
            when(verification.policyStatus()).thenReturn(VerificationStatus.PASS);
            when(verification.taskTypeStatus()).thenReturn(VerificationStatus.PASS);
            when(verification.architectureStatus()).thenReturn(VerificationStatus.PASS);
            when(verification.findings()).thenReturn(List.of());
            AgentRunResult result = mock(AgentRunResult.class);
            when(result.repositoryRoot()).thenReturn(Path.of(baseline.repositorySnapshot().repositoryRoot()));
            when(result.task()).thenReturn(task);
            when(result.taskType()).thenReturn(TaskType.ANALYSIS);
            when(result.plan()).thenReturn(plan);
            when(result.agent()).thenReturn(identity);
            when(result.agentExecutionResult()).thenReturn(execution);
            when(result.executionChangeReport()).thenReturn(delta);
            when(result.verificationReport()).thenReturn(verification);
            when(result.status()).thenReturn(AgentRunStatus.PASS);
            when(result.runTrace()).thenReturn(trace);
            return result;
        }
    }
}
