package io.agentforge.policy;

import io.agentforge.model.ChangeReport;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.ScoreBreakdown;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MinimalChangeEngine {
    public ScoreBreakdown evaluate(ChangeReport change, PolicyConfig.MinimalChangePolicy policy) {
        int score = 100;
        List<String> details = new ArrayList<>();
        int extraFiles = Math.max(0, change.files().size() - policy.freeFiles());
        int filePenalty = Math.min(policy.maxFilePenalty(), extraFiles * policy.perExtraFile());
        score = deduct(score, filePenalty, details, "Files above free allowance");
        int extraModules = Math.max(0, change.modules().size() - policy.freeModules());
        int modulePenalty = Math.min(policy.maxModulePenalty(), extraModules * policy.perExtraModule());
        score = deduct(score, modulePenalty, details, "Modules above free allowance");
        if (change.configChange()) score = deduct(score, policy.configChangePenalty(), details, "Configuration changed");
        if (change.dependencyChange()) score = deduct(score, policy.dependencyChangePenalty(), details, "Dependency descriptor changed");
        if (change.databaseChange()) score = deduct(score, policy.databaseChangePenalty(), details, "Database change detected");
        if (change.securityChange()) score = deduct(score, policy.securityChangePenalty(), details, "Security code changed");
        if (change.agentsChange()) score = deduct(score, policy.agentsChangePenalty(), details, "Agent governance context changed");
        if (details.isEmpty()) details.add("No minimal-change penalties applied");
        return new ScoreBreakdown("Minimal Change", Math.max(0, score), details);
    }

    private int deduct(int score, int points, List<String> details, String reason) {
        if (points > 0) details.add("-" + points + " " + reason);
        return score - Math.max(0, points);
    }
}
