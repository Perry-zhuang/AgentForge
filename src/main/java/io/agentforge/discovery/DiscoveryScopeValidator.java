package io.agentforge.discovery;

import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.PolicyConfig;
import io.agentforge.policy.PathPolicyMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryScopeValidator {
    public DiscoveryValidation validate(DiscoveryRequest request, DiscoveryResult result, EngineeringPlan plan) {
        if (request == null || result == null || plan == null) throw new IllegalArgumentException("Discovery validation inputs are required");
        List<String> reasons = new ArrayList<>();
        List<String> primary = result.proposedPrimaryScope();
        if (primary.isEmpty()) reasons.add("Proposed PRIMARY scope is empty");
        if (!result.unknowns().isEmpty()) reasons.add("Discovery contains unresolved unknowns: " + result.unknowns());
        Set<String> allScope = new LinkedHashSet<>();
        allScope.addAll(primary);
        allScope.addAll(result.proposedSecondaryScope());
        allScope.addAll(result.referenceFiles());
        PolicyConfig policy = request.policy();
        if (allScope.size() > policy.change().maxFiles()) reasons.add("Discovery scope exceeds change.max_files");
        for (String path : primary) {
            if (policy.change().deny().stream().anyMatch(pattern -> PathPolicyMatcher.matches(pattern, path))) {
                reasons.add("PRIMARY path is denied: " + path);
            }
            if (policy.change().protectedPaths().stream().anyMatch(pattern -> PathPolicyMatcher.matches(pattern, path))) {
                reasons.add("PRIMARY path is protected: " + path);
            }
            if (request.existingDirtyPaths().contains(path)) reasons.add("PRIMARY overlaps existing dirty state: " + path);
            boolean evidenced = result.findings().stream().anyMatch(finding -> finding.evidence().contains(path))
                    || result.relevantFlows().stream().anyMatch(flow -> flow.files().contains(path));
            if (!evidenced) reasons.add("PRIMARY lacks discovery evidence: " + path);
        }
        Set<String> modules = new LinkedHashSet<>();
        primary.forEach(path -> modules.add(module(path, plan.repository().structure().modules())));
        if (modules.size() > policy.risk().maxModules()) reasons.add("PRIMARY scope exceeds module limit");
        List<String> readOnly = new ArrayList<>();
        readOnly.addAll(result.proposedSecondaryScope());
        readOnly.addAll(result.referenceFiles());
        return new DiscoveryValidation(reasons.isEmpty() ? DiscoveryValidation.Status.APPROVED
                : DiscoveryValidation.Status.REJECTED, reasons, reasons.isEmpty() ? primary : List.of(), readOnly);
    }

    private String module(String path, List<String> modules) {
        return modules.stream().filter(module -> !module.equals(".") && path.startsWith(module + "/"))
                .max(java.util.Comparator.comparingInt(String::length)).orElse(".");
    }
}
