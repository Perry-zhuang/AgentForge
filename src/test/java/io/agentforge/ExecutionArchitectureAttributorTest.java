package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionArchitectureAttributorTest {
    private static final String SOURCE = "src/main/java/demo/Controller.java";
    private final ExecutionArchitectureAttributor attributor = new ExecutionArchitectureAttributor();

    @Test
    void addedViolationSourceIsDeterministicallyAttributedToExecution() {
        var result = attributor.attribute(architecture(true), execution(
                new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.ADDED,
                        SOURCE, null, true, true)));

        assertThat(result.report().contractViolations()).hasSize(1);
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void currentViolationInModifiedExistingSourceIsNotClaimedAsExecutionIntroduced() {
        var result = attributor.attribute(architecture(true), execution(
                new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.MODIFIED,
                        SOURCE, null, true, false)));

        assertThat(result.report().contractViolations()).isEmpty();
        assertThat(result.limitations()).singleElement().asString()
                .contains("cannot be attributed without an architecture baseline");
    }

    @Test
    void addedDependencyTargetDoesNotMisattributeViolationInUnchangedSource() {
        var result = attributor.attribute(architecture(true), execution(
                new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.ADDED,
                        "src/main/java/demo/Mapper.java", null, true, true)));

        assertThat(result.report().contractViolations()).isEmpty();
        assertThat(result.limitations()).singleElement().asString()
                .contains("other current violations cannot be attributed");
    }

    @Test
    void stagingOnlyAndNoExecutionChangesNeverLeakCurrentArchitectureViolations() {
        var staged = attributor.attribute(architecture(true), execution(
                new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.GIT_STATE_CHANGED,
                        SOURCE, null, false, true)));
        var unchanged = attributor.attribute(architecture(true), execution());

        assertThat(staged.report().contractViolations()).isEmpty();
        assertThat(staged.limitations()).isEmpty();
        assertThat(unchanged.report().contractViolations()).isEmpty();
        assertThat(unchanged.limitations()).isEmpty();
    }

    @Test
    void missingContractProducesNoExecutionArchitectureViolations() {
        var result = attributor.attribute(architecture(false), execution(
                new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.ADDED,
                        SOURCE, null, true, true)));

        assertThat(result.report().contractConfigured()).isFalse();
        assertThat(result.report().contractViolations()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }

    private ExecutionChangeReport execution(ExecutionChangeReport.ExecutionChange... changes) {
        return new ExecutionChangeReport("/repo", "main", "main", false, "abc", "abc", false,
                List.of(changes));
    }

    private ArchitectureReport architecture(boolean configured) {
        DependencyEdge violation = new DependencyEdge("demo.Controller", "demo.Mapper",
                JavaRole.CONTROLLER, JavaRole.MAPPER);
        Map<String, JavaTypeInfo> nodes = Map.of(
                "demo.Controller", new JavaTypeInfo("demo.Controller", "Controller", "demo",
                        JavaRole.CONTROLLER, SOURCE),
                "demo.Mapper", new JavaTypeInfo("demo.Mapper", "Mapper", "demo",
                        JavaRole.MAPPER, "src/main/java/demo/Mapper.java"));
        return new ArchitectureReport(new DependencyGraph(nodes, List.of(violation)), Map.of(), Map.of(),
                List.of(violation), List.of(violation), configured,
                ArchitectureAnalysisMode.LIGHTWEIGHT, List.of());
    }
}
