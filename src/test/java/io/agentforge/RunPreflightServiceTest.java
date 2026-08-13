package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.RunPreflight;
import io.agentforge.model.TaskType;
import io.agentforge.planning.RunPreflightService;
import io.agentforge.planning.TaskTypeClassifier;
import org.junit.jupiter.api.Test;

class RunPreflightServiceTest {
    private final TaskTypeClassifier classifier = new TaskTypeClassifier();
    private final RunPreflightService preflight = new RunPreflightService();

    @Test
    void allowsEveryResolvedCompatibleClassification() {
        assertAllowed("分析流程");
        assertAllowed("修复故障");
        assertAllowed("新增接口");
        assertAllowed("接入 CMP");
        assertAllowed("重构服务");
    }

    @Test
    void allowsUnknownReadOnlyWithWarningAndBlocksUnsafeUnknowns() {
        RunPreflight readOnly = preflight.evaluate(classifier.classifyFacts("看看用户这块，只读", null));
        assertThat(readOnly.status()).isEqualTo(RunPreflight.Status.ALLOWED_WITH_WARNING);
        assertThat(readOnly.reason()).isEqualTo(RunPreflight.Reason.UNKNOWN_READ_ONLY);

        assertBlocked("把用户这块修改一下", RunPreflight.Reason.TASK_TYPE_UNRESOLVED);
        assertBlocked("把用户这块处理一下", RunPreflight.Reason.TASK_TYPE_UNRESOLVED);
        assertBlocked("处理多个用户的密码", RunPreflight.Reason.UNKNOWN_HIGH_SECURITY);
    }

    @Test
    void overridePreservesDetectionAndSecurityButCannotDefeatReadOnlyIntent() {
        var overridden = classifier.classifyFacts("把用户这块处理一下 password", TaskType.FEATURE);
        assertThat(overridden.detectedType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(overridden.effectiveType()).isEqualTo(TaskType.FEATURE);
        assertThat(overridden.source()).isEqualTo(io.agentforge.model.ClassificationSource.USER_OVERRIDE);
        assertThat(overridden.securityImpact()).isEqualTo(io.agentforge.model.SecurityImpact.HIGH);
        assertThat(preflight.evaluate(overridden).allowed()).isTrue();

        var conflict = classifier.classifyFacts("只分析，不修改源码", TaskType.FEATURE);
        assertThat(preflight.evaluate(conflict).reason()).isEqualTo(
                RunPreflight.Reason.TASK_TYPE_OVERRIDE_CONFLICTS_WITH_EXPLICIT_READ_ONLY_INTENT);
    }

    private void assertAllowed(String task) {
        assertThat(preflight.evaluate(classifier.classifyFacts(task, null)).allowed()).as(task).isTrue();
    }

    private void assertBlocked(String task, RunPreflight.Reason reason) {
        RunPreflight result = preflight.evaluate(classifier.classifyFacts(task, null));
        assertThat(result.status()).as(task).isEqualTo(RunPreflight.Status.BLOCKED);
        assertThat(result.reason()).as(task).isEqualTo(reason);
    }
}
