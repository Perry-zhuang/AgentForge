package io.agentforge.runtrace;

import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.application.AgentRunException;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.AgentRunResult;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.discovery.DiscoveryEvidence;
import io.agentforge.model.TaskSpec;

public interface RunTraceService {
    default RunTraceReference prepare(AgentRunRequest request, EngineeringPlan plan, String renderedPrompt,
                                      ExecutionBaseline baseline, CodingAgentIdentity agent) {
        return prepare(request, plan, new io.agentforge.intent.TaskSpecFactory().create(plan), renderedPrompt,
                baseline, agent);
    }

    RunTraceReference prepare(AgentRunRequest request, EngineeringPlan plan, TaskSpec taskSpec, String renderedPrompt,
                              ExecutionBaseline baseline, CodingAgentIdentity agent);

    void persistAgentResult(RunTraceReference trace, AgentExecutionResult result);

    default void persistDiscovery(RunTraceReference trace, DiscoveryEvidence evidence) {}

    RunTraceReference complete(RunTraceReference trace, AgentRunResult result);

    void fail(RunTraceReference trace, AgentRunException.Phase phase, String message,
              AgentExecutionResult agentResult, ExecutionChangeReport executionDelta);
}
