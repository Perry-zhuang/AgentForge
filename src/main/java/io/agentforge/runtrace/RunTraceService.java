package io.agentforge.runtrace;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.application.AgentRunException;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;

public interface RunTraceService {
    RunTraceReference prepare(AgentRunRequest request, EngineeringPlan plan, String renderedPrompt,
                              ExecutionBaseline baseline, CodingAgentIdentity agent);

    void persistAgentResult(RunTraceReference trace, AgentExecutionResult result);

    RunTraceReference complete(RunTraceReference trace, AgentRunResult result);

    void fail(RunTraceReference trace, AgentRunException.Phase phase, String message,
              AgentExecutionResult agentResult, ExecutionChangeReport executionDelta);
}
