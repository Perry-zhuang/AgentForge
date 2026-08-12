package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.TextReportRenderer;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.AuditReport;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.ProjectTechnology;
import io.agentforge.model.Recommendation;
import io.agentforge.model.RecommendationCategory;
import io.agentforge.model.RecommendationPriority;
import io.agentforge.model.RecommendationReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TextReportRendererTest {
    private final TextReportRenderer renderer = new TextReportRenderer();

    @Test
    void rendersLightweightTestabilityAndOnlyTopFiveRecommendations() {
        List<Recommendation> recommendations = IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new Recommendation("id-" + index, RecommendationCategory.CONTEXT,
                        RecommendationPriority.MEDIUM, "Recommendation " + index, "reason", "action", index,
                        List.of("evidence " + index)))
                .toList();

        String output = renderer.audit(audit(new RecommendationReport(recommendations)));

        assertThat(output).contains(
                "Analysis Mode            LIGHTWEIGHT",
                "Source Files             1312",
                "Test Files               3",
                "Test File Ratio          0.23%",
                "Recommended Threshold    10%",
                "1. [MEDIUM] Recommendation 1",
                "5. [MEDIUM] Recommendation 5",
                "Evidence:",
                "Estimated Overall Impact: +1");
        assertThat(output).doesNotContain("Recommendation 6");
    }

    @Test
    void rendersExplicitMessageWhenThereAreNoRecommendations() {
        String output = renderer.audit(audit(new RecommendationReport(List.of())));

        assertThat(output).contains("Top Recommendations", "No high-value recommendations.");
    }

    private AuditReport audit(RecommendationReport recommendations) {
        Path root = Path.of("/tmp/demo");
        RepositoryInfo repository = new RepositoryInfo(root, false, null, "N/A", "N/A", false, List.of());
        RepositoryStructure structure = new RepositoryStructure(List.of("."), Map.of(), 1_312, 3, List.of());
        ContextReport context = new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(),
                Map.of(), List.of(), List.of(), false, ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
        RepositoryReport report = new RepositoryReport(repository,
                new ProjectTechnology(Set.of(), Set.of(), Set.of(), Set.of()), structure, context, architecture);
        return new AuditReport(report, List.of(), 0, 0.10, recommendations, List.of());
    }
}
