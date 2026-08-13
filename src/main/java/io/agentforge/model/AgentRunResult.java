package io.agentforge.model;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.CodingAgentIdentity;
import java.nio.file.Path;

/** Aggregate process, execution-delta, and boundary-verification results without conflating their statuses. */
public record AgentRunResult(
        Path repositoryRoot,
        String task,
        TaskType taskType,
        CodingAgentIdentity agent,
        EngineeringPlan plan,
        EngineeringPrompt prompt,
        AgentExecutionResult agentExecutionResult,
        ExecutionChangeReport executionChangeReport,
        VerificationReport verificationReport,
        AgentRunStatus status
) {
    public AgentRunResult {
        if (repositoryRoot == null || task == null || task.isBlank() || taskType == null || agent == null
                || plan == null || prompt == null || agentExecutionResult == null || executionChangeReport == null
                || verificationReport == null || status == null) {
            throw new IllegalArgumentException("Agent run result facts are required");
        }
        if (!repositoryRoot.equals(plan.repository().repository().path())) {
            throw new IllegalArgumentException("Agent run repository and plan repository must agree");
        }
        if (!task.equals(plan.task()) || taskType != plan.taskType() || !task.equals(prompt.task())
                || taskType != prompt.taskType()) {
            throw new IllegalArgumentException("Agent run task, plan, and prompt must agree");
        }
        if (!agent.equals(agentExecutionResult.agent())) {
            throw new IllegalArgumentException("Agent run identity and process result must agree");
        }
        if (verificationReport.baseline() != VerificationReport.Baseline.EXECUTION_DELTA
                || verificationReport.changeSet().executionChangeReport().isEmpty()
                || !verificationReport.changeSet().executionChangeReport().orElseThrow()
                        .equals(executionChangeReport)) {
            throw new IllegalArgumentException("Agent run delta and verification facts must agree");
        }
        AgentRunStatus expected = AgentRunStatus.decide(
                agentExecutionResult.status(), verificationReport.overallStatus());
        if (status != expected) {
            throw new IllegalArgumentException("Agent run aggregate status must be " + expected);
        }
    }
}
