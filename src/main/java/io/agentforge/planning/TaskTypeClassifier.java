package io.agentforge.planning;

import io.agentforge.model.ClassificationSignal;
import io.agentforge.model.ClassificationSource;
import io.agentforge.model.MutationMode;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.TaskClassification;
import io.agentforge.model.TaskType;
import io.agentforge.model.TaskTypeConfidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Local, deterministic, bilingual classification without repository or process access. */
@Component
public class TaskTypeClassifier {
    private static final Terms READ_ONLY = terms(
            List.of("只分析", "只读", "不修改源码", "不要修改源码", "不改源码", "不修改代码", "不要修改代码", "仅分析", "仅排查"),
            List.of("read-only", "read only", "do not modify", "do not modify source", "do not change source",
                    "no source changes", "analysis only", "analyze only", "inspect only"));
    private static final Terms ANALYSIS = terms(
            List.of("分析", "排查", "调查", "审计", "梳理", "确认", "检查", "定位", "追踪", "验证现状"),
            List.of("analysis", "analyze", "analyzes", "analyzed", "analyzing", "analyse", "analyses", "analysed",
                    "analysing", "investigate", "inspect", "audit", "review", "trace", "diagnose", "examine"));
    private static final Terms BUGFIX = terms(
            List.of("修复", "解决错误", "修正", "纠正", "报错", "异常", "故障", "不生效", "失败", "错误行为", "回归问题"),
            List.of("fix", "fixes", "fixed", "fixing", "bug", "repair", "resolve", "correct", "broken",
                    "failure", "fails", "error", "regression", "defect"));
    private static final Terms FEATURE = actionTerms(
            List.of("新增", "增加", "添加", "实现", "新增功能", "新增接口", "新增能力", "提供功能", "扩展能力"),
            List.of("add", "adds", "added", "adding", "create", "creates", "created", "creating", "implement",
                    "implements", "implemented", "implementing", "introduce", "new feature", "new endpoint",
                    "new capability", "enable", "extend"));
    private static final Terms FEATURE_SUPPORT = terms(
            List.of("支持", "接口", "批量", "入口", "能力"),
            List.of("support", "endpoint", "controller", "api", "batch", "capability"));
    private static final Terms INTEGRATION = terms(
            List.of("接入", "集成", "对接", "打通", "联调", "同步到第三方", "第三方同步"),
            List.of("integrate", "integrates", "integrated", "integrating", "integration", "connect",
                    "interface with", "bridge", "adapter", "sync with", "hook into"));
    private static final Terms REFACTOR = terms(
            List.of("重构", "抽取", "拆分", "整理代码结构", "收敛", "去重复", "解耦", "保持行为不变"),
            List.of("refactor", "refactors", "refactored", "refactoring", "extract", "restructure", "cleanup",
                    "clean up", "simplify", "consolidate", "decouple", "preserve behavior", "without behavior change"));
    private static final Terms BEHAVIOR_CHANGE = terms(
            List.of("改成", "改为", "变更为", "设置为", "默认密码"),
            List.of("as the default", "default password", "so every", "so all"));
    private static final Terms SOURCE_WRITE = terms(
            List.of("新增", "增加", "添加", "实现", "修复", "修改", "更改", "重构", "接入", "集成", "删除", "替换", "升级"),
            List.of("add", "adds", "added", "adding", "create", "creates", "created", "creating", "implement",
                    "implements", "implemented", "implementing", "fix", "fixes", "fixed", "fixing", "modify",
                    "change", "refactor", "refactors", "refactored", "refactoring", "integrate", "integrates",
                    "integrated", "integrating", "remove", "replace", "upgrade"));
    private static final Terms PASSWORD = terms(List.of("密码", "口令"), List.of("password", "passwd"));
    private static final Terms SALT = terms(List.of("盐值"), List.of("salt"));
    private static final Terms CREDENTIAL = terms(
            List.of("密钥", "令牌", "认证", "鉴权", "权限", "凭证", "登录"),
            List.of("secret", "credential", "credentials", "token", "authentication", "authorization", "permission", "login"));
    private static final Terms PRIVILEGED = terms(List.of("管理员", "超级管理员"),
            List.of("admin", "superadmin", "administrator", "root"));
    private static final Terms BATCH = terms(List.of("批量", "多个用户"), List.of("all users", "multiple users", "batch"));
    private static final Terms API = terms(List.of("接口", "入口"), List.of("controller", "api", "endpoint"));

    public Result classify(String task) {
        TaskClassification classification = classifyFacts(task, null);
        return new Result(classification.effectiveType(), primarySignal(classification));
    }

    public TaskClassification classifyFacts(String task, TaskType override) {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("Task must not be blank");
        String normalized = task.toLowerCase(Locale.ROOT);
        boolean readOnly = READ_ONLY.matches(normalized);
        boolean analysis = ANALYSIS.matches(normalized);
        boolean bugfix = BUGFIX.matches(normalized);
        boolean feature = FEATURE.matches(normalized);
        boolean integration = INTEGRATION.matches(normalized);
        boolean refactor = REFACTOR.matches(normalized);
        boolean behaviorChange = BEHAVIOR_CHANGE.matches(normalized);
        boolean featureSupport = FEATURE_SUPPORT.matches(normalized);
        boolean write = SOURCE_WRITE.matches(normalized);
        boolean password = PASSWORD.matches(normalized);
        boolean salt = SALT.matches(normalized);
        boolean credential = CREDENTIAL.matches(normalized);
        boolean privileged = PRIVILEGED.matches(normalized);
        boolean batch = BATCH.matches(normalized);
        boolean api = API.matches(normalized);

        // A natural API + batch mutation request is a deterministic feature even without “新增”.
        boolean naturalFeature = !feature && api && featureSupport && batch && write;
        feature |= naturalFeature;

        TaskType detected = resolve(readOnly, analysis, bugfix, feature, integration, refactor, behaviorChange);
        MutationMode mutation = readOnly ? MutationMode.READ_ONLY
                : detected == TaskType.ANALYSIS ? MutationMode.READ_ONLY
                : write || isWriteType(detected) ? MutationMode.SOURCE_WRITE : MutationMode.UNKNOWN;
        SecurityImpact security = password || salt || credential ? SecurityImpact.HIGH : SecurityImpact.NONE;
        TaskTypeConfidence confidence = detected == TaskType.UNKNOWN ? TaskTypeConfidence.LOW
                : naturalFeature || strongCount(bugfix, feature, integration, refactor, analysis) == 1
                        ? TaskTypeConfidence.HIGH : TaskTypeConfidence.MEDIUM;

        Set<ClassificationSignal> signals = new LinkedHashSet<>();
        add(signals, analysis, ClassificationSignal.ANALYSIS);
        add(signals, bugfix, ClassificationSignal.BUGFIX);
        add(signals, feature, ClassificationSignal.FEATURE);
        add(signals, featureSupport, ClassificationSignal.FEATURE_SUPPORT);
        add(signals, integration, ClassificationSignal.INTEGRATION);
        add(signals, refactor, ClassificationSignal.REFACTOR);
        add(signals, behaviorChange, ClassificationSignal.BEHAVIOR_CHANGE);
        add(signals, readOnly, ClassificationSignal.EXPLICIT_READ_ONLY);
        add(signals, write, ClassificationSignal.SOURCE_WRITE);
        add(signals, api, ClassificationSignal.API_CHANGE);
        add(signals, password, ClassificationSignal.PASSWORD);
        add(signals, salt, ClassificationSignal.SALT);
        add(signals, credential, ClassificationSignal.SECURITY_CREDENTIAL);
        add(signals, privileged, ClassificationSignal.PRIVILEGED_ACCOUNT);
        add(signals, batch, ClassificationSignal.BATCH_MUTATION);

        return new TaskClassification(detected, detected, confidence, mutation, security,
                ClassificationSource.DETECTED, new ArrayList<>(signals), readOnly).withOverride(override);
    }

    private TaskType resolve(boolean readOnly, boolean analysis, boolean bugfix, boolean feature,
                             boolean integration, boolean refactor, boolean behaviorChange) {
        if (readOnly && analysis) return TaskType.ANALYSIS;
        if (refactor && behaviorChange) return TaskType.UNKNOWN;
        int writes = strongCount(bugfix, feature, integration, refactor);
        if (writes > 1) return TaskType.UNKNOWN;
        if (bugfix) return TaskType.BUGFIX;
        if (feature) return TaskType.FEATURE;
        if (integration) return TaskType.INTEGRATION;
        if (refactor) return TaskType.REFACTOR;
        return analysis ? TaskType.ANALYSIS : TaskType.UNKNOWN;
    }

    private int strongCount(boolean... values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private boolean isWriteType(TaskType type) {
        return type == TaskType.BUGFIX || type == TaskType.FEATURE || type == TaskType.INTEGRATION
                || type == TaskType.REFACTOR;
    }

    private void add(Set<ClassificationSignal> result, boolean condition, ClassificationSignal signal) {
        if (condition) result.add(signal);
    }

    private static Terms terms(List<String> chinese, List<String> english) {
        return new Terms(chinese, english.stream().map(term -> Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(term) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList());
    }

    private static Terms actionTerms(List<String> chinese, List<String> english) {
        return new Terms(chinese, english.stream().map(term -> Pattern.compile(
                "(?<![A-Za-z0-9_.])" + Pattern.quote(term) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList());
    }

    private record Terms(List<String> chinese, List<Pattern> english) {
        boolean matches(String task) {
            return chinese.stream().anyMatch(task::contains)
                    || english.stream().anyMatch(pattern -> pattern.matcher(task).find());
        }
    }

    public record Result(TaskType type, String signal) {}

    private static String primarySignal(TaskClassification classification) {
        if (classification.effectiveType() == TaskType.UNKNOWN) {
            return "No deterministic task-type keyword matched or write intent was unresolved";
        }
        return classification.signals().stream().map(Enum::name).findFirst()
                .orElse(classification.effectiveType().name());
    }

}
