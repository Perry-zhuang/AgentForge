package io.agentforge.planning;

import io.agentforge.git.ChangeSignalClassifier;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.policy.PolicyPathMatcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanRiskEngine {
    public PlanRisk evaluate(TaskInspectionReport inspection, PolicyConfig policy,
                             GitBaseline baseline, PlanScopeOverlap overlap, int primaryCandidateCount,
                             boolean architectureContractConfigured) {
        List<PlanRisk.Factor> factors = new ArrayList<>();
        List<String> relevantPaths = inspection.relevantFiles().stream().map(file -> file.path()).toList();
        boolean denied = relevantPaths.stream()
                .anyMatch(path -> PolicyPathMatcher.matchesAny(policy.change().deny(), path));
        boolean protectedPath = relevantPaths.stream()
                .anyMatch(path -> PolicyPathMatcher.matchesAny(policy.change().protectedPaths(), path));
        ChangeSignalClassifier.Signals signals = ChangeSignalClassifier.classify(relevantPaths);

        addIf(factors, denied, 20, "Denied policy path appears in the inspect results");
        addIf(factors, protectedPath, 20, "Protected policy path appears in the inspect results");
        addIf(factors, signals.security(), 20, "Security path appears in the inspect results");
        addIf(factors, signals.database(), 15, "Database path appears in the inspect results");
        addIf(factors, signals.dependency(), 15, "Dependency descriptor appears in the inspect results");
        addIf(factors, primaryCandidateCount > 5, 10, "More than 5 files qualify as PRIMARY candidates");
        addIf(factors, inspection.relevantModules().size() > 2, 10,
                "Inspect results span more than 2 modules");
        addDirtyFactor(factors, baseline.changedFiles());
        if (overlap.primaryOverlap()) {
            factors.add(new PlanRisk.Factor(15, "Existing changes overlap PRIMARY plan scope"));
        } else if (overlap.secondaryOverlap()) {
            factors.add(new PlanRisk.Factor(5, "Existing changes overlap SECONDARY plan scope"));
        }
        addIf(factors, !architectureContractConfigured, 5, "Architecture contract is not configured");
        addIf(factors, !baseline.available(), 10, "Git baseline is not available");

        int score = factors.stream().mapToInt(PlanRisk.Factor::points).sum();
        PlanRisk.Level level = score >= 40 ? PlanRisk.Level.HIGH
                : score >= 20 ? PlanRisk.Level.MEDIUM : PlanRisk.Level.LOW;
        return new PlanRisk(score, level, factors);
    }

    private void addIf(List<PlanRisk.Factor> factors, boolean condition, int points, String reason) {
        if (condition) factors.add(new PlanRisk.Factor(points, reason));
    }

    private void addDirtyFactor(List<PlanRisk.Factor> factors, int changedFiles) {
        int points = changedFiles > 20 ? 20 : changedFiles > 5 ? 10 : changedFiles > 0 ? 5 : 0;
        if (points > 0) {
            factors.add(new PlanRisk.Factor(points,
                    "Working tree contains " + changedFiles + " existing changed "
                            + (changedFiles == 1 ? "file" : "files")));
        }
    }
}
