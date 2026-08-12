package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanRisk;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.planning.PlanRiskEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanRiskTest {
    @TempDir
    Path root;

    private final PlanRiskEngine engine = new PlanRiskEngine();

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "3, 5",
            "5, 5",
            "6, 10",
            "10, 10",
            "20, 10",
            "21, 20",
            "42, 20"
    })
    void calibratesDirtyRiskAtFixedBoundaries(int changedFiles, int expectedPoints) {
        PlanRisk risk = evaluate(changedFiles, PlanScopeOverlap.none());

        if (expectedPoints == 0) {
            assertThat(risk.factors()).isEmpty();
        } else {
            assertThat(risk.factors()).singleElement().satisfies(factor -> {
                assertThat(factor.points()).isEqualTo(expectedPoints);
                assertThat(factor.reason()).isEqualTo(
                        "Working tree contains " + changedFiles + " existing changed files");
            });
        }
    }

    @Test
    void primaryOverlapTakesPrecedenceOverSecondaryOverlap() {
        PlanRisk primary = evaluate(0, new PlanScopeOverlap(1, List.of("A.java"), true, false));
        PlanRisk secondary = evaluate(0, new PlanScopeOverlap(1, List.of("B.java"), false, true));
        PlanRisk both = evaluate(0, new PlanScopeOverlap(2, List.of("A.java", "B.java"), true, true));

        assertThat(primary.factors()).extracting(PlanRisk.Factor::points).containsExactly(15);
        assertThat(secondary.factors()).extracting(PlanRisk.Factor::points).containsExactly(5);
        assertThat(both.factors()).extracting(PlanRisk.Factor::points).containsExactly(15);
        assertThat(both.factors()).extracting(PlanRisk.Factor::reason)
                .containsExactly("Existing changes overlap PRIMARY plan scope");
    }

    private PlanRisk evaluate(int changedFiles, PlanScopeOverlap overlap) {
        TaskInspectionReport inspection = mock(TaskInspectionReport.class);
        when(inspection.relevantFiles()).thenReturn(List.of());
        when(inspection.relevantModules()).thenReturn(List.of());
        GitBaseline baseline = new GitBaseline(true, "main", "0123456789abcdef",
                changedFiles == 0 ? GitBaseline.WorkingTreeState.CLEAN : GitBaseline.WorkingTreeState.DIRTY,
                changedFiles);
        return engine.evaluate(inspection, new ConfigLoader().loadPolicy(root).config(), baseline, overlap,
                0, true);
    }
}
