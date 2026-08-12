package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.TaskType;
import io.agentforge.planning.TaskTypeClassifier;
import org.junit.jupiter.api.Test;

class TaskTypeClassifierTest {
    private final TaskTypeClassifier classifier = new TaskTypeClassifier();

    @Test
    void classifiesRequiredTaskExamples() {
        assertType("修复登录401异常", TaskType.BUGFIX, "修复");
        assertType("增加用户导出功能", TaskType.FEATURE, "增加");
        assertType("接入云信达同步", TaskType.INTEGRATION, "接入");
        assertType("分析历史告警流程", TaskType.ANALYSIS, "分析");
    }

    @Test
    void classifiesRefactorAndUnknown() {
        assertType("重构订单服务", TaskType.REFACTOR, "重构");
        assertThat(classifier.classify("整理订单说明").type()).isEqualTo(TaskType.UNKNOWN);
    }

    @Test
    void usesFixedTypePrecedenceAndEarliestSignalWithinAType() {
        assertType("分析并修复同步 error", TaskType.BUGFIX, "修复");
        assertType("异常修复登录", TaskType.BUGFIX, "异常");
        assertType("implement a sync adapter", TaskType.FEATURE, "implement");
    }

    private void assertType(String task, TaskType type, String signal) {
        TaskTypeClassifier.Result result = classifier.classify(task);
        assertThat(result.type()).isEqualTo(type);
        assertThat(result.signal()).isEqualTo(signal);
    }
}
