package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.inspection.RelevantFileEngine;
import io.agentforge.inspection.TaskAnalyzer;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.FileType;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.SourceFileIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelevantFileEngineTest {
    private final RelevantFileEngine engine = new RelevantFileEngine();
    private final TaskAnalyzer analyzer = new TaskAnalyzer();

    @Test
    void ranksUserAndLoginFilesWellAboveUnrelatedOrderFile(@TempDir Path root) {
        List<SourceFileIndex> sources = List.of(
                java("src/main/java/demo/user/UserController.java", "demo.user", "UserController", JavaRole.CONTROLLER,
                        "user login endpoint"),
                java("src/main/java/demo/auth/LoginService.java", "demo.auth", "LoginService", JavaRole.SERVICE,
                        "authenticate user login"),
                java("src/main/java/demo/order/OrderService.java", "demo.order", "OrderService", JavaRole.SERVICE,
                        "create order"));

        var result = evaluate(root, "user login", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("UserController", "LoginService");
        assertThat(result.relevantFiles().get(0).score()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void addsOnlyOneHopArchitectureNeighbors(@TempDir Path root) {
        SourceFileIndex a = java("src/A.java", "demo", "A", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex b = java("src/BackupHistory.java", "demo", "BackupHistory", JavaRole.SERVICE, "backup history");
        SourceFileIndex c = java("src/C.java", "demo", "C", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex d = java("src/D.java", "demo", "D", JavaRole.COMPONENT, "unrelated");
        List<SourceFileIndex> sources = List.of(a, b, c, d);
        DependencyGraph graph = graph(sources, List.of(edge(a, b), edge(b, c), edge(c, d)));

        var result = evaluate(root, "backup history", sources, graph, List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .contains("A", "BackupHistory").doesNotContain("C", "D");
        assertThat(result.relevantFiles().stream().filter(file -> file.className().orElse("").equals("A")).findFirst().orElseThrow().score())
                .isEqualTo(10);
        assertThat(result.relatedFlow()).hasSize(1);
    }

    @Test
    void indexesRecognizedFrontendSourcesAndHonorsLimit(@TempDir Path root) throws Exception {
        Path login = write(root, "frontend/src/auth/LoginPanel.vue", "<template>User login authentication</template>");
        Path order = write(root, "frontend/src/order/OrderPanel.ts", "export const order = true");
        RepositoryStructure structure = structure(List.of("frontend"));

        var result = engine.evaluate(root, List.of(login, order), structure, emptyContext(),
                analyzer.analyze("user login"), 1, List.of(), new DependencyGraph(Map.of(), List.of()));

        assertThat(result.relevantFiles()).hasSize(1);
        assertThat(result.relevantFiles().get(0).path()).isEqualTo("frontend/src/auth/LoginPanel.vue");
        assertThat(result.relevantModules()).extracting(module -> module.path()).containsExactly("frontend");
        assertThat(result.relevantSourceAreas()).extracting(area -> area.name()).containsExactly("frontend/src/auth");
    }

    @Test
    void contextBoostRequiresTaskAndCandidateIdentityInSameDocument(@TempDir Path root) throws Exception {
        Path readme = write(root, "README.md", "Login changes are owned by LoginService.");
        SourceFileIndex login = java("src/LoginService.java", "demo", "LoginService", JavaRole.SERVICE, "login");
        SourceFileIndex other = java("src/Other.java", "demo", "Other", JavaRole.OTHER, "login");

        var result = engine.evaluate(root, List.of(readme), structure(List.of(".")), emptyContext(),
                analyzer.analyze("login"), 10, List.of(login, other), emptyGraph(List.of(login, other)));

        var loginResult = result.relevantFiles().stream().filter(file -> file.className().orElse("").equals("LoginService")).findFirst().orElseThrow();
        assertThat(loginResult.evidence()).anyMatch(evidence -> evidence.signal().equals("CONTEXT"));
        assertThat(result.relevantFiles().stream().filter(file -> file.className().orElse("").equals("Other")).findFirst())
                .isEmpty();
    }

    @Test
    void doesNotCombineKeywordsFromDistantUnrelatedSections(@TempDir Path root) {
        String content = "user\nlogin";
        SourceFileIndex source = java("src/Unrelated.java", "demo", "Unrelated", JavaRole.OTHER, content);

        var result = evaluate(root, "user login", List.of(source), emptyGraph(List.of(source)), List.of("."), 10);

        assertThat(result.relevantFiles()).isEmpty();
    }

    @Test
    void prioritizesAnnotatedFlowTypeOverPassiveConstantWithSameKeywords(@TempDir Path root) {
        SourceFileIndex service = java("src/LoginFlowService.java", "demo", "LoginFlowService",
                JavaRole.SERVICE, "user login permission");
        SourceFileIndex constants = java("src/LoginConstants.java", "demo", "LoginConstants",
                JavaRole.OTHER, "user login permission");

        var result = evaluate(root, "user login permission", List.of(service, constants),
                emptyGraph(List.of(service, constants)), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("LoginFlowService", "LoginConstants");
        assertThat(result.relevantFiles().get(0).evidence()).anyMatch(evidence -> evidence.signal().equals("JAVA_ROLE"));
    }

    @Test
    void prioritizesControllerAndServiceRolesOverMapperOnEqualLexicalEvidence(@TempDir Path root) {
        SourceFileIndex service = java("src/UserService.java", "demo", "UserService",
                JavaRole.SERVICE, "用户 权限");
        SourceFileIndex mapper = java("src/UserMapper.java", "demo", "UserMapper",
                JavaRole.MAPPER, "用户 权限");
        List<SourceFileIndex> sources = List.of(mapper, service);

        var result = evaluate(root, "用户权限", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("UserService", "UserMapper");
    }

    @Test
    void boostsOnlyAlreadyMatchedFilesInConcentratedBusinessPackage(@TempDir Path root) {
        SourceFileIndex anchor = java("src/auth/service/LoginService.java", "demo.auth.service", "LoginService",
                JavaRole.SERVICE, "user login");
        SourceFileIndex permission = java("src/auth/controller/PermissionController.java", "demo.auth.controller",
                "PermissionController", JavaRole.CONTROLLER, "user permission");
        SourceFileIndex unrelated = java("src/auth/service/OrderService.java", "demo.auth.service", "OrderService",
                JavaRole.SERVICE, "orders only");
        SourceFileIndex isolated = java("src/export/ExportService.java", "demo.export.service", "ExportService",
                JavaRole.SERVICE, "user permission");
        List<SourceFileIndex> sources = List.of(anchor, permission, unrelated, isolated);

        var result = evaluate(root, "user login permission", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles().stream().filter(file -> file.className().orElse("").equals("PermissionController"))
                .findFirst().orElseThrow().evidence()).anyMatch(evidence -> evidence.signal().equals("SOURCE_AREA_COHORT"));
        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .doesNotContain("OrderService");
        assertThat(result.relevantFiles().stream().filter(file -> file.className().orElse("").equals("ExportService"))
                .findFirst().orElseThrow().evidence()).noneMatch(evidence -> evidence.signal().equals("SOURCE_AREA_COHORT"));
    }

    @Test
    void rewardsNearbyShortChineseConceptsWithoutOvervaluingDistantMentions(@TempDir Path root) {
        SourceFileIndex nearby = java("src/near/Nearby.java", "demo.near", "Nearby", JavaRole.OTHER,
                "执行同步告警信息");
        SourceFileIndex distant = java("src/far/Distant.java", "demo.far", "Distant", JavaRole.OTHER,
                "备份" + "x".repeat(1000) + "历史" + "x".repeat(1000) + "告警");
        List<SourceFileIndex> sources = List.of(nearby, distant);

        var result = evaluate(root, "备份历史告警同步", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("Nearby").doesNotContain("Distant");
    }

    @Test
    void ranksExactChineseCompoundAboveFilesMatchingOnlyOneShortConcept(@TempDir Path root) {
        SourceFileIndex exact = java("src/HistoryAlertApi.java", "demo.api", "HistoryAlertApi", JavaRole.OTHER,
                "查询历史告警");
        SourceFileIndex alertHistory = java("src/WarnService.java", "demo.warn", "WarnService", JavaRole.SERVICE,
                "告警规则关联后查询历史记录");
        SourceFileIndex historyOnly = java("src/HistoryService.java", "demo.history", "HistoryService", JavaRole.SERVICE,
                "查询历史记录");
        List<SourceFileIndex> sources = List.of(historyOnly, alertHistory, exact);

        var result = evaluate(root, "历史告警", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("HistoryAlertApi", "WarnService");
        assertThat(result.relevantFiles().get(0).score()).isGreaterThanOrEqualTo(45);
        assertThat(result.relevantFiles()).noneMatch(file -> file.className().orElse("").equals("HistoryService"));
    }

    @Test
    void doesNotExpandExactChinesePhraseThroughMethodNames(@TempDir Path root) {
        SourceFileIndex contract = java("src/HistoryAlertApi.java", "demo.api", "HistoryAlertApi", JavaRole.OTHER,
                "/** 历史告警 */ List<Alert> getHistoryAlert(String id);");
        SourceFileIndex implementation = java("src/HistoryAlertService.java", "demo.service", "HistoryAlertService",
                JavaRole.SERVICE, "public List<Alert> getHistoryAlert(String id) { return List.of(); }");
        SourceFileIndex unrelated = java("src/HistoryService.java", "demo.history", "HistoryService",
                JavaRole.SERVICE, "public List<Item> getHistory(String id) { return List.of(); }");
        List<SourceFileIndex> sources = List.of(contract, implementation, unrelated);

        var result = evaluate(root, "历史告警", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("HistoryAlertApi").doesNotContain("HistoryAlertService");
        assertThat(result.relevantFiles().get(0).evidence())
                .noneMatch(evidence -> evidence.signal().equals("RELATED_IDENTIFIER"));
        assertThat(result.relevantFiles()).noneMatch(file -> file.className().orElse("").equals("HistoryService"));
    }

    @Test
    void doesNotPropagateShortGenericIdentifierFromChinesePhrase(@TempDir Path root) {
        SourceFileIndex declaration = java("src/JobApi.java", "demo.api", "JobApi", JavaRole.OTHER,
                "/** 备份历史 */ Result jobs(String id);");
        SourceFileIndex broadCaller = java("src/JobController.java", "demo.jobs", "JobController",
                JavaRole.CONTROLLER, "public Result list() { return jobs(id); }");
        List<SourceFileIndex> sources = List.of(declaration, broadCaller);

        var result = evaluate(root, "备份历史告警同步", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .containsExactly("JobApi");
        assertThat(result.relevantFiles()).allMatch(file -> file.evidence().stream()
                .noneMatch(evidence -> evidence.signal().equals("RELATED_IDENTIFIER")));
    }

    @Test
    void fullCoreCoverageClearlyOutranksSingleCoreMatches(@TempDir Path root) {
        SourceFileIndex both = java("src/A.java", "demo", "A", JavaRole.OTHER, "查询历史告警记录");
        SourceFileIndex history = java("src/B.java", "demo", "B", JavaRole.OTHER, "查询历史记录");
        SourceFileIndex alert = java("src/C.java", "demo", "C", JavaRole.OTHER, "查询告警记录");
        SourceFileIndex restore = java("src/D.java", "demo", "D", JavaRole.SERVICE, "恢复历史记录");
        List<SourceFileIndex> sources = List.of(both, history, alert, restore);

        var result = evaluate(root, "历史告警", sources, emptyGraph(sources), List.of("."), 10);

        var bothResult = file(result, "A");
        assertThat(bothResult.score()).isGreaterThanOrEqualTo(45);
        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .doesNotContain("B", "C", "D");
        assertThat(bothResult.evidence()).anyMatch(evidence -> evidence.signal().equals("FULL_CORE_COVERAGE"));
    }

    @Test
    void completePlainClassMatchOutranksSingleCoreArchitectureRoles(@TempDir Path root) {
        SourceFileIndex plain = java("src/Plain.java", "demo", "Plain", JavaRole.OTHER, "历史告警");
        SourceFileIndex service = java("src/OrderService.java", "demo", "OrderService", JavaRole.SERVICE, "历史");
        SourceFileIndex controller = java("src/HistoryController.java", "demo", "HistoryController",
                JavaRole.CONTROLLER, "历史");
        List<SourceFileIndex> sources = List.of(service, controller, plain);

        var result = evaluate(root, "历史告警", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(result.relevantFiles().get(0).className()).contains("Plain");
        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .doesNotContain("OrderService", "HistoryController");
    }

    @Test
    void architectureRolesAreWeakAndComponentAddsZero(@TempDir Path root) {
        SourceFileIndex service = java("src/Service.java", "demo", "Service", JavaRole.SERVICE, "历史告警");
        SourceFileIndex component = java("src/Component.java", "demo", "Component", JavaRole.COMPONENT, "历史告警");
        List<SourceFileIndex> sources = List.of(service, component);

        var result = evaluate(root, "历史告警", sources, emptyGraph(sources), List.of("."), 10);

        assertThat(file(result, "Service").evidence())
                .anyMatch(evidence -> evidence.signal().equals("JAVA_ROLE") && evidence.points() == 2);
        assertThat(file(result, "Component").evidence())
                .anyMatch(evidence -> evidence.signal().equals("JAVA_ROLE") && evidence.points() == 0);
    }

    @Test
    void weakSeedDoesNotExplodeDependenciesButStrongSeedPropagatesOneHop(@TempDir Path root) {
        SourceFileIndex weak = java("src/Weak.java", "demo", "Weak", JavaRole.SERVICE, "历史");
        SourceFileIndex a = java("src/A.java", "demo", "A", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex b = java("src/B.java", "demo", "B", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex c = java("src/C.java", "demo", "C", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex d = java("src/D.java", "demo", "D", JavaRole.COMPONENT, "unrelated");
        SourceFileIndex strong = java("src/Strong.java", "demo", "Strong", JavaRole.SERVICE, "历史告警");
        SourceFileIndex x = java("src/X.java", "demo", "X", JavaRole.COMPONENT, "unrelated");
        List<SourceFileIndex> sources = List.of(weak, a, b, c, d, strong, x);
        DependencyGraph graph = graph(sources, List.of(edge(a, weak), edge(b, weak), edge(c, weak), edge(d, weak),
                edge(x, strong)));

        var result = evaluate(root, "历史告警", sources, graph, List.of("."), 15);

        assertThat(result.relevantFiles()).extracting(file -> file.className().orElse(""))
                .contains("Strong", "X").doesNotContain("Weak", "A", "B", "C", "D");
        assertThat(file(result, "X").evidence())
                .anyMatch(evidence -> evidence.signal().equals("DEPENDENCY") && evidence.points() == 10);
    }

    private RelevantFileEngine.Result evaluate(Path root, String task, List<SourceFileIndex> sources,
                                               DependencyGraph graph, List<String> modules, int limit) {
        return engine.evaluate(root, List.of(), structure(modules), emptyContext(), analyzer.analyze(task), limit, sources, graph);
    }

    private static io.agentforge.model.RelevantFile file(RelevantFileEngine.Result result, String className) {
        return result.relevantFiles().stream().filter(file -> file.className().orElse("").equals(className))
                .findFirst().orElseThrow();
    }

    private static SourceFileIndex java(String path, String packageName, String name, JavaRole role, String content) {
        return new SourceFileIndex(path, FileType.JAVA, packageName + "." + name, name, packageName, role,
                Set.of(), Set.of(), content);
    }

    private static DependencyEdge edge(SourceFileIndex from, SourceFileIndex to) {
        return new DependencyEdge(from.qualifiedName(), to.qualifiedName(), from.role(), to.role());
    }

    private static DependencyGraph graph(List<SourceFileIndex> sources, List<DependencyEdge> edges) {
        Map<String, JavaTypeInfo> nodes = sources.stream().collect(java.util.stream.Collectors.toMap(
                SourceFileIndex::qualifiedName,
                source -> new JavaTypeInfo(source.qualifiedName(), source.className(), source.packageName(), source.role(), source.path())));
        return new DependencyGraph(nodes, edges);
    }

    private static DependencyGraph emptyGraph(List<SourceFileIndex> sources) {
        return graph(sources, List.of());
    }

    private static RepositoryStructure structure(List<String> modules) {
        return new RepositoryStructure(modules, new EnumMap<>(FileType.class), 0, 0, List.of());
    }

    private static ContextReport emptyContext() {
        return new ContextReport(false, 0, false, false, false, 0, 0,
                false, false, false, false, Map.of(), List.of());
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
