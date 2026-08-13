package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.agent.CodingAgentType;
import io.agentforge.cli.RunRenderer;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.AgentRunStatus;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import io.agentforge.model.ViolationSeverity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRendererTest {
    @Test
    void rendersAllAggregateDecisionsWithAgentOutputButWithoutRawStderr() {
        assertDecision(AgentRunStatus.PASS, "AGENT RUN PASS");
        assertDecision(AgentRunStatus.WARNING, "AGENT RUN WARNING");
        assertDecision(AgentRunStatus.VERIFICATION_FAILED, "AGENT RUN VERIFICATION FAILED");
        assertDecision(AgentRunStatus.AGENT_FAILED, "AGENT RUN AGENT FAILED");
        assertDecision(AgentRunStatus.AGENT_TIMED_OUT, "AGENT RUN TIMED OUT");
        assertDecision(AgentRunStatus.AGENT_INTERRUPTED, "AGENT RUN INTERRUPTED");
        assertDecision(AgentRunStatus.AGENT_START_FAILED, "AGENT RUN START FAILED");
    }

    @Test
    void rendersOnlyExistingAggregateFactsInStableSectionOrder() {
        AgentRunResult result = result(AgentRunStatus.VERIFICATION_FAILED);

        String rendered = new RunRenderer().render(result);

        assertThat(rendered).contains(
                "AgentForge Run",
                "Path                     /tmp/run-renderer",
                "Task                     修复登录异常",
                "Task Type                BUGFIX",
                "Agent                    CODEX",
                "PRIMARY                  1",
                "SECONDARY                1",
                "REFERENCE                1",
                "Status                   SUCCESS",
                "Exit Code                0",
                "stderr Captured          YES",
                "Agent Output",
                "raw stdout body",
                "Files                    1",
                "MODIFIED src/main/java/demo/OutsideService.java CONTENT_CHANGED GIT_STATE_CHANGED",
                "Scope                    FAIL",
                "Policy                   PASS",
                "Task-Type                PASS",
                "Architecture             NOT CONFIGURED",
                "OUT_OF_SCOPE_MODIFICATION",
                "AGENT RUN VERIFICATION FAILED");
        assertThat(rendered).doesNotContain("raw stderr body", "prepared secret prompt");
        assertThat(rendered.indexOf("Agent Execution")).isLessThan(rendered.indexOf("Execution Delta"));
        assertThat(rendered.indexOf("Execution Delta")).isLessThan(rendered.indexOf("Verification"));
        assertThat(rendered.indexOf("Verification")).isLessThan(rendered.indexOf("Decision"));
    }

    @Test
    void preservesChineseMarkdownOutputAndMarksEmptyOutput() {
        AgentRunResult populated = result(AgentRunStatus.PASS);
        when(populated.agentExecutionResult().stdout()).thenReturn("  根据分析：\n\n- 用户不会重复创建\n");
        assertThat(new RunRenderer().render(populated)).contains("  根据分析：\n\n- 用户不会重复创建\n");

        AgentRunResult empty = result(AgentRunStatus.PASS);
        when(empty.agentExecutionResult().stdout()).thenReturn("");
        assertThat(new RunRenderer().render(empty)).contains("No agent output produced.");
    }

    private void assertDecision(AgentRunStatus status, String decision) {
        assertThat(new RunRenderer().render(result(status))).contains(decision);
    }

    private AgentRunResult result(AgentRunStatus status) {
        AgentRunResult result = mock(AgentRunResult.class);
        CodingAgentIdentity identity = mock(CodingAgentIdentity.class);
        AgentExecutionResult process = mock(AgentExecutionResult.class);
        ExecutionChangeReport delta = mock(ExecutionChangeReport.class);
        VerificationReport verification = mock(VerificationReport.class);
        EngineeringBoundarySnapshot boundary = mock(EngineeringBoundarySnapshot.class);
        ExecutionChangeReport.ExecutionChange change = new ExecutionChangeReport.ExecutionChange(
                ExecutionChangeReport.ChangeType.MODIFIED, "src/main/java/demo/OutsideService.java",
                null, true, true);
        VerificationFinding finding = new VerificationFinding(VerificationFinding.Category.SCOPE,
                ViolationSeverity.ERROR, "OUT_OF_SCOPE_MODIFICATION",
                "src/main/java/demo/OutsideService.java", "outside frozen scope");

        when(result.repositoryRoot()).thenReturn(Path.of("/tmp/run-renderer"));
        when(result.task()).thenReturn("修复登录异常");
        when(result.taskType()).thenReturn(TaskType.BUGFIX);
        when(result.plan()).thenReturn(PromptTestFixtures.plan(TaskType.BUGFIX));
        when(result.agent()).thenReturn(identity);
        when(result.agentExecutionResult()).thenReturn(process);
        when(result.executionChangeReport()).thenReturn(delta);
        when(result.verificationReport()).thenReturn(verification);
        when(result.status()).thenReturn(status);
        when(identity.type()).thenReturn(CodingAgentType.CODEX);
        when(process.status()).thenReturn(AgentExecutionStatus.SUCCESS);
        when(process.processExitCode()).thenReturn(0);
        when(process.stdout()).thenReturn("raw stdout body");
        when(process.stderr()).thenReturn("raw stderr body");
        when(process.failureMessage()).thenReturn("");
        when(delta.changes()).thenReturn(List.of(change));
        when(verification.executionBoundary()).thenReturn(boundary);
        when(verification.overallStatus()).thenReturn(VerificationStatus.FAIL);
        when(verification.scopeStatus()).thenReturn(VerificationStatus.FAIL);
        when(verification.policyStatus()).thenReturn(VerificationStatus.PASS);
        when(verification.taskTypeStatus()).thenReturn(VerificationStatus.PASS);
        when(verification.architectureStatus()).thenReturn(VerificationStatus.PASS);
        when(verification.findings()).thenReturn(List.of(finding));
        when(boundary.primaryPaths()).thenReturn(List.of("primary.java"));
        when(boundary.secondaryPaths()).thenReturn(List.of("secondary.java"));
        when(boundary.referencePaths()).thenReturn(List.of("reference.java"));
        when(boundary.contractConfigured()).thenReturn(false);
        return result;
    }
}
