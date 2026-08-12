package io.agentforge.risk;

import io.agentforge.model.ChangeReport;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.RiskFactor;
import io.agentforge.model.RiskLevel;
import io.agentforge.model.RiskReport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RiskEngine {
    public RiskReport evaluate(ChangeReport change, PolicyReport policyReport, PolicyConfig config) {
        PolicyConfig.RiskPolicy policy = config.risk();
        List<RiskFactor> factors = new ArrayList<>();
        if (policyReport.failed()) add(factors, policy.policyViolation(), "Policy violation detected");
        if (change.dependencyChange()) add(factors, policy.dependencyChange(), "Dependency descriptor changed");
        if (change.securityChange()) add(factors, policy.securityChange(), "Security files changed");
        if (change.databaseChange()) add(factors, policy.databaseChange(), "Database migration or SQL changed");
        if (change.files().size() > config.change().maxFiles()) add(factors, policy.filesOverLimit(), "Changed files exceed policy maximum");
        if (change.modules().size() > policy.maxModules()) add(factors, policy.modulesOverLimit(), "More than " + policy.maxModules() + " modules changed");
        if (change.controllerChange()) add(factors, policy.controllerChange(), "API controller changed");
        if (change.agentsChange()) add(factors, policy.agentsChange(), "Agent governance context changed");
        if (change.configChange()) add(factors, policy.configChange(), "Configuration changed");
        int score = factors.stream().mapToInt(RiskFactor::points).sum();
        RiskLevel level = score >= policy.criticalThreshold() ? RiskLevel.CRITICAL
                : score >= policy.highThreshold() ? RiskLevel.HIGH
                : score >= policy.mediumThreshold() ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskReport(score, level, factors);
    }

    private void add(List<RiskFactor> factors, int points, String reason) {
        if (points > 0) factors.add(new RiskFactor(points, reason));
    }
}
