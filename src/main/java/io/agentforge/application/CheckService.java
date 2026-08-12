package io.agentforge.application;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.VerificationChangeSet;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationRequest;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.verification.CheckEngine;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CheckService {
    private final PlanService planService;
    private final DiffAuditService diffAuditService;
    private final BaselineService baselineService;
    private final ConfigLoader configLoader;
    private final PolicyEngine policyEngine;
    private final ExecutionArchitectureAttributor architectureAttributor;
    private final CheckEngine checkEngine;

    public CheckService(PlanService planService, DiffAuditService diffAuditService, BaselineService baselineService,
                        ConfigLoader configLoader, PolicyEngine policyEngine,
                        ExecutionArchitectureAttributor architectureAttributor, CheckEngine checkEngine) {
        this.planService = planService;
        this.diffAuditService = diffAuditService;
        this.baselineService = baselineService;
        this.configLoader = configLoader;
        this.policyEngine = policyEngine;
        this.architectureAttributor = architectureAttributor;
        this.checkEngine = checkEngine;
    }

    public VerificationReport check(VerificationRequest request) {
        EngineeringPlan plan = planService.plan(new EngineeringPlanRequest(request.path(), request.task()));
        if (!request.executionDeltaMode()) {
            return checkEngine.verify(plan, diffAuditService.audit(plan.repository().repository().path()));
        }
        return checkExecution(plan, request);
    }

    private VerificationReport checkExecution(EngineeringPlan plan, VerificationRequest request) {
        ExecutionChangeReport execution = baselineService.compare(
                plan.repository().repository().path(), request.baselineFile());
        ConfigLoader.LoadedPolicy loadedPolicy = configLoader.loadPolicy(plan.repository().repository().path());
        ExecutionArchitectureAttributor.Result architecture = architectureAttributor.attribute(
                plan.repository().architecture(), execution);
        PolicyReport policy = policyEngine.evaluate(policyChanges(execution), loadedPolicy.config(),
                architecture.report());
        VerificationChangeSet facts = VerificationChangeSet.executionDelta(plan.repository().repository(),
                execution, policy, architecture.report(), loadedPolicy.defaultUsed(), architecture.limitations());
        return checkEngine.verify(plan, facts);
    }

    private List<ChangedFile> policyChanges(ExecutionChangeReport execution) {
        return execution.changes().stream().map(change -> new ChangedFile(
                switch (change.type()) {
                    case ADDED -> ChangeStatus.ADDED;
                    case MODIFIED, GIT_STATE_CHANGED -> ChangeStatus.MODIFIED;
                    case DELETED -> ChangeStatus.DELETED;
                    case RENAMED -> ChangeStatus.RENAMED;
                }, change.path(), change.oldPath())).toList();
    }
}
