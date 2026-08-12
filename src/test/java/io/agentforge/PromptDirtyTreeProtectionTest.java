package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.PromptRenderer;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptDirtyTreeProtectionTest {
    @Test
    void cleanRepositoryReportsCleanBaselineWithoutInventingOverlap() {
        String output = render(PromptTestFixtures.plan(TaskType.BUGFIX));

        assertThat(output).contains("Working Tree: CLEAN", "The repository working tree is clean.");
        assertThat(output).doesNotContain("PRIMARY overlap is present.", "SECONDARY overlap is present.");
    }

    @Test
    void dirtyPrimaryOverlapEmitsAllDestructiveGitProtections() {
        GitBaseline baseline = dirty(42);
        PlanScopeOverlap overlap = new PlanScopeOverlap(3,
                List.of("src/main/java/demo/PrimaryService.java"), true, true);
        PlanRisk risk = new PlanRisk(40, PlanRisk.Level.HIGH, List.of(
                new PlanRisk.Factor(20, "Working tree contains 42 existing changed files"),
                new PlanRisk.Factor(15, "Existing changes overlap PRIMARY plan scope"),
                new PlanRisk.Factor(5, "Architecture contract is not configured")));

        String output = render(PromptTestFixtures.plan(
                TaskType.BUGFIX, baseline, overlap, false, true, risk));

        assertThat(output).contains(
                "The repository already contains 42 changed files.",
                "3 existing changed files overlap the planned scope.",
                "PRIMARY overlap is present.",
                "Do not reset existing changes.",
                "Do not discard existing changes.",
                "Do not overwrite unrelated work.",
                "Inspect the existing diff before modifying overlapping files.",
                "Preserve changes unrelated to this task.",
                "Do not use git checkout -- <file>.",
                "Do not use git restore <file>.",
                "Do not use git reset --hard.",
                "Do not clean untracked files.",
                "Before modifying any overlapping PRIMARY file, inspect its existing diff and preserve unrelated edits.",
                "Risk: HIGH", "Score: 40", "Proceed conservatively.");
    }

    @Test
    void secondaryOnlyOverlapDoesNotClaimPrimaryOverlap() {
        PlanScopeOverlap overlap = new PlanScopeOverlap(1,
                List.of("src/main/java/demo/SecondaryService.java"), false, true);
        PlanRisk risk = new PlanRisk(30, PlanRisk.Level.MEDIUM, List.of(
                new PlanRisk.Factor(20, "Working tree contains 42 existing changed files"),
                new PlanRisk.Factor(5, "Existing changes overlap SECONDARY plan scope"),
                new PlanRisk.Factor(5, "Architecture contract is not configured")));

        String output = render(PromptTestFixtures.plan(
                TaskType.BUGFIX, dirty(42), overlap, false, true, risk));

        assertThat(output).contains("SECONDARY overlap is present.");
        assertThat(output).doesNotContain(
                "PRIMARY overlap is present.",
                "Before modifying any overlapping PRIMARY file");
    }

    private GitBaseline dirty(int files) {
        return new GitBaseline(true, "main", "0123456789abcdef", GitBaseline.WorkingTreeState.DIRTY, files);
    }

    private String render(io.agentforge.model.EngineeringPlan plan) {
        return new PromptRenderer().render(new PromptEngine().create(plan));
    }
}
