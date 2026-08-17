package io.agentforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.PlanConfidence;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanScopeOverlap;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.TaskClassification;
import io.agentforge.model.TaskType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryTriggerTest {
    private final DiscoveryTrigger trigger = new DiscoveryTrigger();
    private final io.agentforge.model.PolicyConfig policy = new ConfigLoader().loadPolicy(Path.of(".")).config();

    @Test void highSecurityRequiresDiscovery() { assertRequired(plan(SecurityImpact.HIGH, TaskType.FEATURE, PlanConfidence.HIGH, false, List.of(file("a", ".")))); }
    @Test void integrationRequiresDiscovery() { assertRequired(plan(SecurityImpact.NONE, TaskType.INTEGRATION, PlanConfidence.HIGH, false, List.of(file("a", ".")))); }
    @Test void lowConfidenceRequiresDiscovery() { assertRequired(plan(SecurityImpact.NONE, TaskType.BUGFIX, PlanConfidence.LOW, false, List.of(file("a", ".")))); }
    @Test void primaryDirtyOverlapRequiresDiscovery() { assertRequired(plan(SecurityImpact.NONE, TaskType.BUGFIX, PlanConfidence.HIGH, true, List.of(file("a", ".")))); }

    @Test
    void simpleBugfixSkipsDiscovery() {
        assertThat(trigger.decide(plan(SecurityImpact.NONE, TaskType.BUGFIX, PlanConfidence.HIGH, false,
                List.of(file("src/main/java/Calculator.java", "."))), policy).status())
                .isEqualTo(DiscoveryDecision.Status.SKIPPED);
    }

    private void assertRequired(EngineeringPlan plan) {
        assertThat(trigger.decide(plan, policy).status()).isEqualTo(DiscoveryDecision.Status.REQUIRED);
    }

    private EngineeringPlan plan(SecurityImpact security, TaskType type, PlanConfidence confidence,
                                 boolean overlap, List<RelevantFile> primary) {
        EngineeringPlan plan = mock(EngineeringPlan.class);
        TaskClassification classification = mock(TaskClassification.class);
        when(classification.securityImpact()).thenReturn(security);
        when(plan.taskClassification()).thenReturn(classification);
        when(plan.taskType()).thenReturn(type);
        when(plan.confidence()).thenReturn(confidence);
        when(plan.scope()).thenReturn(new PlanScope(primary, List.of(), List.of()));
        List<String> overlapPaths = overlap ? List.of(primary.get(0).path()) : List.of();
        when(plan.scopeOverlap()).thenReturn(new PlanScopeOverlap(overlap ? 1 : 0,
                overlapPaths, overlap, false));
        return plan;
    }

    private RelevantFile file(String path, String module) {
        RelevantFile file = mock(RelevantFile.class);
        when(file.path()).thenReturn(path);
        when(file.module()).thenReturn(module);
        return file;
    }
}
