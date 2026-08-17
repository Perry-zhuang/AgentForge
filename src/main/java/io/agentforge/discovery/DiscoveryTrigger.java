package io.agentforge.discovery;

import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.TaskType;
import io.agentforge.policy.PathPolicyMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryTrigger {
    public DiscoveryDecision decide(EngineeringPlan plan, PolicyConfig policy) {
        if (plan == null || policy == null) throw new IllegalArgumentException("Discovery trigger inputs are required");
        List<DiscoveryDecision.Reason> reasons = new ArrayList<>();
        if (plan.taskClassification().securityImpact() == SecurityImpact.HIGH) reasons.add(DiscoveryDecision.Reason.HIGH_SECURITY);
        if (plan.taskType() == TaskType.INTEGRATION) reasons.add(DiscoveryDecision.Reason.INTEGRATION_TASK);
        if (plan.confidence() == PlanConfidence.LOW) reasons.add(DiscoveryDecision.Reason.LOW_SCOPE_CONFIDENCE);
        Set<String> modules = new LinkedHashSet<>(plan.scope().primary().stream().map(file -> file.module()).toList());
        if (modules.size() > 1) reasons.add(DiscoveryDecision.Reason.MULTI_MODULE);
        if (plan.scopeOverlap().primaryOverlap()) reasons.add(DiscoveryDecision.Reason.PRIMARY_DIRTY_OVERLAP);
        if (plan.scope().primary().size() > 3) reasons.add(DiscoveryDecision.Reason.LARGE_PRIMARY_SCOPE);
        boolean protectedOrDatabase = plan.scope().primary().stream().map(file -> file.path()).anyMatch(path ->
                path.startsWith("database/") || path.contains("/db/migration/")
                        || policy.change().deny().stream().anyMatch(pattern -> PathPolicyMatcher.matches(pattern, path))
                        || policy.change().protectedPaths().stream().anyMatch(pattern -> PathPolicyMatcher.matches(pattern, path)));
        if (protectedOrDatabase) reasons.add(DiscoveryDecision.Reason.PROTECTED_OR_DATABASE_SCOPE);
        return reasons.isEmpty() ? DiscoveryDecision.skipped()
                : new DiscoveryDecision(DiscoveryDecision.Status.REQUIRED, reasons);
    }
}
