package io.agentforge.planning;

import io.agentforge.model.TaskType;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TaskTypeClassifier {
    private static final List<Rule> RULES = List.of(
            new Rule(TaskType.BUGFIX, List.of("修复", "bug", "error", "异常")),
            new Rule(TaskType.FEATURE, List.of("新增", "增加", "support", "implement")),
            new Rule(TaskType.REFACTOR, List.of("重构", "refactor")),
            new Rule(TaskType.INTEGRATION, List.of("接入", "集成", "sync", "integration")),
            new Rule(TaskType.ANALYSIS, List.of("分析", "inspect", "trace"))
    );

    public Result classify(String task) {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Task must not be blank");
        String normalized = task.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            String signal = firstSignal(normalized, rule.keywords());
            if (signal != null) return new Result(rule.type(), signal);
        }
        return new Result(TaskType.UNKNOWN, "No deterministic task-type keyword matched");
    }

    private String firstSignal(String task, List<String> keywords) {
        String result = null;
        int bestIndex = Integer.MAX_VALUE;
        for (String keyword : keywords) {
            int index = task.indexOf(keyword);
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                result = keyword;
            }
        }
        return result;
    }

    public record Result(TaskType type, String signal) {}

    private record Rule(TaskType type, List<String> keywords) {}
}
