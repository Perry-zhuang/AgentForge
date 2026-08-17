package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ClassificationSignal;
import io.agentforge.model.MutationMode;
import io.agentforge.model.RunPreflight;
import io.agentforge.model.SecurityImpact;
import io.agentforge.model.TaskClassification;
import io.agentforge.model.TaskType;
import io.agentforge.model.TaskTypeConfidence;
import io.agentforge.planning.TaskTypeClassifier;
import io.agentforge.planning.RunPreflightService;
import org.junit.jupiter.api.Test;

class TaskTypeClassifierTest {
    private final TaskTypeClassifier classifier = new TaskTypeClassifier();
    private final RunPreflightService preflight = new RunPreflightService();

    @Test
    void pureRefactorPreservingBehavior() {
        assertClassification("重构 AdminService 的代码结构，提取重复方法，保持现有业务行为不变",
                TaskType.REFACTOR, TaskTypeConfidence.HIGH, MutationMode.SOURCE_WRITE, RunPreflight.Status.ALLOWED);
        assertClassification("Refactor AdminService to remove duplicated code without changing behavior.",
                TaskType.REFACTOR, TaskTypeConfidence.HIGH, MutationMode.SOURCE_WRITE, RunPreflight.Status.ALLOWED);
    }

    @Test
    void refactorWithBehaviorChangeFailsClosed() {
        assertFailsClosed("重构用户密码逻辑，把所有新用户默认密码改成 admin");
        assertFailsClosed("Refactor the password logic so every new user uses admin as the default password.");
    }

    @Test
    void pureBugfixWithDomainFeatureWordRemainsBugfix() {
        assertClassification("修复 CalculatorService.add 加法计算错误", TaskType.BUGFIX,
                TaskTypeConfidence.HIGH, MutationMode.SOURCE_WRITE, RunPreflight.Status.ALLOWED);
        assertClassification("Fix the CalculatorService.add calculation bug.", TaskType.BUGFIX,
                TaskTypeConfidence.HIGH, MutationMode.SOURCE_WRITE, RunPreflight.Status.ALLOWED);
    }

    @Test
    void refactorBugfixConflictFailsClosed() {
        assertFailsClosed("重构登录逻辑，修复 token 过期后仍然可以访问接口的问题");
        assertFailsClosed("Refactor the login flow and fix expired tokens remaining valid.");
    }

    @Test
    void refactorFeatureConflictFailsClosed() {
        assertFailsClosed("重构用户接口，新增批量删除用户能力");
    }

    @Test
    void ambiguousPasswordChangeFailsClosed() {
        assertFailsClosed("修改用户密码逻辑，把默认密码改成 admin");
    }

    @Test
    void explicitReadOnlyStillWins() {
        assertClassification("分析如何修复 CMP 用户同步重复账号问题，只分析，不修改源码", TaskType.ANALYSIS,
                TaskTypeConfidence.MEDIUM, MutationMode.READ_ONLY, RunPreflight.Status.ALLOWED);
    }

    @Test
    void securityFactsRemainHighAfterConflict() {
        TaskClassification result = classifier.classifyFacts(
                "重构用户密码逻辑，把所有新用户默认密码改成 admin", null);
        assertThat(result.securityImpact()).isEqualTo(SecurityImpact.HIGH);
        assertThat(result.signals()).contains(ClassificationSignal.PASSWORD,
                ClassificationSignal.PRIVILEGED_ACCOUNT, ClassificationSignal.BEHAVIOR_CHANGE);
    }

    @Test
    void classifiesChineseEnglishAndMixedTasks() {
        assertType("分析登录流程", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertType("修复登录失败", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
        assertType("新增用户接口", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("接入 CMP 系统", TaskType.INTEGRATION, MutationMode.SOURCE_WRITE);
        assertType("重构服务并保持行为不变", TaskType.REFACTOR, MutationMode.SOURCE_WRITE);
        assertType("analyze login flow", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertType("fix the broken login", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
        assertType("add a password endpoint", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("integrate with CMP", TaskType.INTEGRATION, MutationMode.SOURCE_WRITE);
        assertType("refactor auth without behavior change", TaskType.REFACTOR, MutationMode.SOURCE_WRITE);
        assertType("分析 login flow, read-only", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertType("新增 batch endpoint", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("修复 login error", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
    }

    @Test
    void supportsRequiredEnglishInflectionsWithoutNaiveSubstringMatches() {
        assertType("adding an endpoint", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("added an endpoint", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("implementing a capability", TaskType.FEATURE, MutationMode.SOURCE_WRITE);
        assertType("fixed the defect", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
        assertType("refactoring the service", TaskType.REFACTOR, MutationMode.SOURCE_WRITE);
        assertType("integrated with CMP", TaskType.INTEGRATION, MutationMode.SOURCE_WRITE);
        assertType("analyzing the flow", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertThat(classifier.classifyFacts("address user metadata", null).effectiveType())
                .isEqualTo(TaskType.UNKNOWN);
    }

    @Test
    void resolvesReadOnlyWriteAndMixedIntentConflictsDeterministically() {
        assertType("分析如何修复问题，只分析不修改源码", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertType("分析并修复问题", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
        assertType("重构并保持行为不变", TaskType.REFACTOR, MutationMode.SOURCE_WRITE);
        assertType("接入系统并修复旧 bug", TaskType.UNKNOWN, MutationMode.SOURCE_WRITE);
        assertType("把用户这块处理一下", TaskType.UNKNOWN, MutationMode.UNKNOWN);
        assertType("analyze update password SQL, do not modify source", TaskType.ANALYSIS, MutationMode.READ_ONLY);
        assertType("fix insert user failure", TaskType.BUGFIX, MutationMode.SOURCE_WRITE);
    }

    @Test
    void classifiesNaturalBaasPasswordFeatureAndSecurityEvidence() {
        TaskClassification result = classifier.classifyFacts(
                "把非admin跟superadmin的用户都更改密码跟盐值，通过controller输入，支持单个用户、多个用户修改密码", null);
        assertThat(result.effectiveType()).isEqualTo(TaskType.FEATURE);
        assertThat(result.typeConfidence()).isIn(TaskTypeConfidence.MEDIUM, TaskTypeConfidence.HIGH);
        assertThat(result.mutationMode()).isEqualTo(MutationMode.SOURCE_WRITE);
        assertThat(result.securityImpact()).isEqualTo(SecurityImpact.HIGH);
        assertThat(result.signals()).contains(ClassificationSignal.API_CHANGE, ClassificationSignal.PASSWORD,
                ClassificationSignal.SALT, ClassificationSignal.PRIVILEGED_ACCOUNT,
                ClassificationSignal.BATCH_MUTATION);
    }

    @Test
    void securityIsSemanticAndAdminAloneIsNotHigh() {
        assertThat(classifier.classifyFacts("password reset", null).securityImpact()).isEqualTo(SecurityImpact.HIGH);
        assertThat(classifier.classifyFacts("修改密码和盐值", null).securityImpact()).isEqualTo(SecurityImpact.HIGH);
        assertThat(classifier.classifyFacts("change token authentication", null).securityImpact()).isEqualTo(SecurityImpact.HIGH);
        assertThat(classifier.classifyFacts("新增显示名称功能", null).securityImpact()).isEqualTo(SecurityImpact.NONE);
        assertThat(classifier.classifyFacts("新增 AdminController 显示名称", null).securityImpact()).isEqualTo(SecurityImpact.NONE);
    }

    private void assertType(String task, TaskType type, MutationMode mode) {
        TaskClassification result = classifier.classifyFacts(task, null);
        assertThat(result.effectiveType()).as(task).isEqualTo(type);
        assertThat(result.mutationMode()).as(task).isEqualTo(mode);
    }

    private void assertClassification(String task, TaskType type, TaskTypeConfidence confidence,
                                      MutationMode mode, RunPreflight.Status status) {
        TaskClassification result = classifier.classifyFacts(task, null);
        assertThat(result.effectiveType()).as(task).isEqualTo(type);
        assertThat(result.typeConfidence()).as(task).isEqualTo(confidence);
        assertThat(result.mutationMode()).as(task).isEqualTo(mode);
        assertThat(preflight.evaluate(result).status()).as(task).isEqualTo(status);
    }

    private void assertFailsClosed(String task) {
        assertClassification(task, TaskType.UNKNOWN, TaskTypeConfidence.LOW,
                MutationMode.SOURCE_WRITE, RunPreflight.Status.BLOCKED);
    }
}
