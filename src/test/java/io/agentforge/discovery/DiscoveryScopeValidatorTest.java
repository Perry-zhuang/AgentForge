package io.agentforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.TaskClassification;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryScopeValidatorTest {
    private final DiscoveryScopeValidator validator = new DiscoveryScopeValidator();
    private final EngineeringPlan plan = plan();
    private final io.agentforge.model.PolicyConfig policy = new ConfigLoader().loadPolicy(Path.of(".")).config();

    @Test void validPrimaryApproved() { assertThat(validate(result("README.md", List.of(), true)).status()).isEqualTo(DiscoveryValidation.Status.APPROVED); }
    @Test void protectedPathRejected() { assertThat(validate(result("src/main/java/demo/security/Auth.java", List.of(), true)).status()).isEqualTo(DiscoveryValidation.Status.REJECTED); }
    @Test void unknownsRejectMutation() { assertThat(validate(result("README.md", List.of("unclear owner"), true)).status()).isEqualTo(DiscoveryValidation.Status.REJECTED); }
    @Test void evidenceMissingRejected() { assertThat(validate(result("README.md", List.of(), false)).status()).isEqualTo(DiscoveryValidation.Status.REJECTED); }

    @Test
    void oversizedScopeRejected() {
        List<String> secondary = new ArrayList<>();
        for (int i = 0; i < policy.change().maxFiles(); i++) secondary.add("file" + i + ".txt");
        DiscoveryResult result = new DiscoveryResult(List.of(new DiscoveryResult.Finding("e", List.of("README.md"))),
                List.of(), List.of("README.md"), secondary, List.of(), List.of(), List.of(), DiscoveryResult.Confidence.HIGH);
        assertThat(validator.validate(request(), result, plan).status()).isEqualTo(DiscoveryValidation.Status.REJECTED);
    }

    private DiscoveryValidation validate(DiscoveryResult result) { return validator.validate(request(), result, plan); }

    private DiscoveryResult result(String primary, List<String> unknowns, boolean evidence) {
        return new DiscoveryResult(List.of(new DiscoveryResult.Finding("finding", evidence ? List.of(primary) : List.of())),
                List.of(), List.of(primary), List.of(), List.of(), List.of(), unknowns, DiscoveryResult.Confidence.HIGH);
    }

    private DiscoveryRequest request() {
        return new DiscoveryRequest(plan.task(), "a".repeat(64), plan.taskClassification(), Path.of(".").toAbsolutePath(),
                "main", "head", List.of(), List.of(), List.of(), List.of(), List.of(), policy,
                ArchitectureAnalysisMode.LIGHTWEIGHT, Duration.ofSeconds(5));
    }

    private EngineeringPlan plan() {
        EngineeringPlan value = mock(EngineeringPlan.class);
        TaskClassification classification = mock(TaskClassification.class);
        RepositoryReport repository = mock(RepositoryReport.class);
        RepositoryStructure structure = mock(RepositoryStructure.class);
        when(value.task()).thenReturn("修复问题");
        when(value.taskClassification()).thenReturn(classification);
        when(value.repository()).thenReturn(repository);
        when(repository.structure()).thenReturn(structure);
        when(structure.modules()).thenReturn(List.of("."));
        return value;
    }
}
