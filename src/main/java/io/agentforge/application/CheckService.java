package io.agentforge.application;

import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationRequest;
import io.agentforge.verification.CheckEngine;
import org.springframework.stereotype.Component;

@Component
public class CheckService {
    private final PlanService planService;
    private final DiffAuditService diffAuditService;
    private final CheckEngine checkEngine;

    public CheckService(PlanService planService, DiffAuditService diffAuditService, CheckEngine checkEngine) {
        this.planService = planService;
        this.diffAuditService = diffAuditService;
        this.checkEngine = checkEngine;
    }

    public VerificationReport check(VerificationRequest request) {
        EngineeringPlan plan = planService.plan(new EngineeringPlanRequest(request.path(), request.task()));
        return checkEngine.verify(plan, diffAuditService.audit(plan.repository().repository().path()));
    }
}
