package io.agentforge.discovery;

import io.agentforge.agent.AgentExecutionRequest;
import io.agentforge.agent.AgentExecutionResult;
import io.agentforge.agent.AgentExecutionStatus;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentIdentity;
import io.agentforge.baseline.BaselineComparator;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.AgentRunRequest;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.RepositorySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DiscoveryOrchestrator implements DiscoveryCoordinator {
    private final ConfigLoader configLoader;
    private final RepositoryStateCapture stateCapture;
    private final BaselineComparator comparator;
    private final DiscoveryTrigger trigger;
    private final DiscoveryPromptRenderer promptRenderer;
    private final DiscoveryResultParser parser;
    private final DiscoveryScopeValidator validator;

    public DiscoveryOrchestrator(ConfigLoader configLoader, RepositoryStateCapture stateCapture,
                                 BaselineComparator comparator, DiscoveryTrigger trigger,
                                 DiscoveryPromptRenderer promptRenderer, DiscoveryResultParser parser,
                                 DiscoveryScopeValidator validator) {
        this.configLoader = configLoader;
        this.stateCapture = stateCapture;
        this.comparator = comparator;
        this.trigger = trigger;
        this.promptRenderer = promptRenderer;
        this.parser = parser;
        this.validator = validator;
    }

    @Override
    public DiscoveryOutcome discover(EngineeringPlan plan, AgentRunRequest runRequest,
                                     CodingAgentAdapter adapter, CodingAgentIdentity agent) {
        var policy = configLoader.loadPolicy(plan.repository().repository().path()).config();
        DiscoveryDecision decision = trigger.decide(plan, policy);
        if (decision.status() == DiscoveryDecision.Status.SKIPPED) {
            return new DiscoveryOutcome(plan, DiscoveryEvidence.skipped(decision), null);
        }
        RepositorySnapshot before = stateCapture.capture(plan.repository().repository().path());
        DiscoveryRequest request = request(plan, runRequest, policy, before);
        String prompt = promptRenderer.render(request);
        AgentExecutionResult agentResult = adapter.execute(new AgentExecutionRequest(agent,
                plan.repository().repository().path(), prompt, runRequest.timeout(), runRequest.environmentOverrides()));
        RepositorySnapshot after = stateCapture.capture(plan.repository().repository().path());
        ExecutionChangeReport delta = comparator.compare(before, after);
        if (!delta.changes().isEmpty()) {
            throw new DiscoveryException("Discovery modified repository files: "
                    + delta.changes().stream().map(ExecutionChangeReport.ExecutionChange::path).toList(),
                    new DiscoveryEvidence(decision, prompt, agentResult, delta, agentResult.stdout(), null, null));
        }
        if (agentResult.status() != AgentExecutionStatus.SUCCESS) {
            throw new DiscoveryException("Discovery agent did not complete successfully: " + agentResult.status(),
                    new DiscoveryEvidence(decision, prompt, agentResult, delta, agentResult.stdout(), null, null));
        }
        DiscoveryResult result;
        try {
            result = parser.parse(agentResult.stdout(), plan.repository().repository().path());
        } catch (DiscoveryException exception) {
            throw new DiscoveryException(exception.getMessage(),
                    new DiscoveryEvidence(decision, prompt, agentResult, delta, agentResult.stdout(), null, null), exception);
        }
        DiscoveryValidation validation = validator.validate(request, result, plan);
        if (validation.status() != DiscoveryValidation.Status.APPROVED) {
            throw new DiscoveryException("Discovery scope rejected: " + validation.reasons(),
                    new DiscoveryEvidence(decision, prompt, agentResult, delta, agentResult.stdout(), result, validation));
        }
        EngineeringPlan validatedPlan = plan.withDiscoveryScope(validation.frozenPrimary(), validation.readOnlyFiles());
        return new DiscoveryOutcome(validatedPlan,
                new DiscoveryEvidence(decision, prompt, agentResult, delta, agentResult.stdout(), result, validation), after);
    }

    private DiscoveryRequest request(EngineeringPlan plan, AgentRunRequest runRequest,
                                     io.agentforge.model.PolicyConfig policy, RepositorySnapshot snapshot) {
        List<String> evidence = new ArrayList<>();
        plan.scope().primary().forEach(file -> evidence.add(file.path()));
        plan.scope().secondary().forEach(file -> evidence.add(file.path()));
        plan.scope().reference().forEach(file -> evidence.add(file.path()));
        List<String> dirty = snapshot.files().stream().filter(file -> !file.porcelainStatus().equals("  "))
                .map(RepositorySnapshot.RepositoryFileState::path).toList();
        return new DiscoveryRequest(plan.task(), sha(plan.task()), plan.taskClassification(),
                plan.repository().repository().path(), snapshot.branch(), snapshot.head(), evidence,
                plan.scope().primary().stream().map(file -> file.path()).toList(),
                plan.scope().secondary().stream().map(file -> file.path()).toList(),
                plan.scope().reference().stream().map(file -> file.path()).toList(), dirty, policy,
                plan.repository().architecture().analysisMode(), runRequest.timeout());
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
