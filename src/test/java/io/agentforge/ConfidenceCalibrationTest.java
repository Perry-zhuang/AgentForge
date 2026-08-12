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
import io.agentforge.model.RelevanceLevel;
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

class ConfidenceCalibrationTest {
    private final RelevantFileEngine engine = new RelevantFileEngine();
    private final TaskAnalyzer analyzer = new TaskAnalyzer();

    @Test
    void springServiceWithTwoOfFourCoreTokensIsMedium(@TempDir Path root) {
        var file = inspect(root, "备份历史告警同步",
                java("src/AlertService.java", "demo", "AlertService", JavaRole.SERVICE, "告警 同步"));

        assertThat(file.score()).isEqualTo(22);
        assertThat(file.level()).isEqualTo(RelevanceLevel.MEDIUM);
        assertThat(file.confidence().reason()).contains("2/4", "service");
    }

    @Test
    void springServiceWithOneCoreTokenStaysLowEvenAboveTwentyPoints(@TempDir Path root) throws Exception {
        SourceFileIndex worker = java("src/WorkerService.java", "demo", "WorkerService", JavaRole.SERVICE, "alpha");
        SourceFileIndex incomingSeed = java("src/BetaGammaService.java", "demo", "BetaGammaService",
                JavaRole.SERVICE, "beta gamma");
        SourceFileIndex outgoingSeed = java("src/GammaDeltaService.java", "demo", "GammaDeltaService",
                JavaRole.SERVICE, "gamma delta");
        List<SourceFileIndex> sources = List.of(incomingSeed, outgoingSeed, worker);
        List<DependencyEdge> edges = List.of(
                new DependencyEdge(incomingSeed.qualifiedName(), worker.qualifiedName(),
                        incomingSeed.role(), worker.role()),
                new DependencyEdge(worker.qualifiedName(), outgoingSeed.qualifiedName(),
                        worker.role(), outgoingSeed.role()));
        Path readme = root.resolve("README.md");
        Files.writeString(readme, "alpha changes are owned by WorkerService");

        var result = engine.evaluate(root, List.of(readme),
                new RepositoryStructure(List.of("."), new EnumMap<>(FileType.class), 0, 0, List.of()),
                new ContextReport(false, 0, false, false, false, 0, 0,
                        false, false, false, false, Map.of(), List.of()),
                analyzer.analyze("alpha beta gamma delta"), 10, sources, graph(sources, edges));
        var file = result.relevantFiles().stream().filter(item -> item.className().orElse("").equals("WorkerService"))
                .findFirst().orElseThrow();

        assertThat(file.matchedCoreTokens()).containsExactly("alpha");
        assertThat(file.score()).isGreaterThanOrEqualTo(20);
        assertThat(file.level()).isEqualTo(RelevanceLevel.LOW);
    }

    @Test
    void directStructuralEvidenceIsMedium(@TempDir Path root) {
        var file = inspect(root, "login authentication permissions",
                java("src/LoginHandler.java", "demo", "LoginHandler", JavaRole.OTHER, "unrelated"));

        assertThat(file.score()).isGreaterThanOrEqualTo(15);
        assertThat(file.level()).isEqualTo(RelevanceLevel.MEDIUM);
        assertThat(file.confidence().reason()).contains("structural");
    }

    @Test
    void textOnlyHighScoreDoesNotBecomeHigh(@TempDir Path root) {
        var file = inspect(root, "历史告警",
                java("src/Generic.java", "demo", "Generic", JavaRole.OTHER, "历史告警"));

        assertThat(file.score()).isGreaterThanOrEqualTo(40);
        assertThat(file.level()).isEqualTo(RelevanceLevel.MEDIUM);
    }

    @Test
    void strongStructuralFileWithHighScoreIsHigh(@TempDir Path root) {
        var file = inspect(root, "login authentication",
                java("src/LoginAuthenticationService.java", "demo", "LoginAuthenticationService",
                        JavaRole.SERVICE, "login authentication"));

        assertThat(file.score()).isGreaterThanOrEqualTo(40);
        assertThat(file.level()).isEqualTo(RelevanceLevel.HIGH);
    }

    @Test
    void cappedEnumAlwaysStaysLow(@TempDir Path root) {
        var file = inspect(root, "历史告警",
                java("src/GenericEnum.java", "demo", "GenericEnum", JavaRole.OTHER,
                        "enum GenericEnum { 历史告警 }"));

        assertThat(file.score()).isEqualTo(24);
        assertThat(file.level()).isEqualTo(RelevanceLevel.LOW);
        assertThat(file.confidence().reason()).startsWith("Confidence is capped");
    }

    @Test
    void componentServiceSuffixDoesNotUseMediumResponsibilityRule(@TempDir Path root) {
        var file = inspect(root, "备份历史告警同步",
                java("src/ExternalServiceImpl.java", "demo", "ExternalServiceImpl", JavaRole.COMPONENT,
                        "告警 同步"));

        assertThat(file.score()).isEqualTo(20);
        assertThat(file.level()).isEqualTo(RelevanceLevel.LOW);
    }

    @Test
    void unannotatedServiceInterfaceDoesNotUseMediumResponsibilityRule(@TempDir Path root) {
        var file = inspect(root, "备份历史告警同步",
                java("src/IAlertService.java", "demo", "IAlertService", JavaRole.OTHER, "告警 同步"));

        assertThat(file.score()).isEqualTo(20);
        assertThat(file.level()).isEqualTo(RelevanceLevel.LOW);
    }

    private io.agentforge.model.RelevantFile inspect(Path root, String task, SourceFileIndex source) {
        Map<String, JavaTypeInfo> nodes = Map.of(source.qualifiedName(),
                new JavaTypeInfo(source.qualifiedName(), source.className(), source.packageName(),
                        source.role(), source.path()));
        var result = engine.evaluate(root, List.of(),
                new RepositoryStructure(List.of("."), new EnumMap<>(FileType.class), 0, 0, List.of()),
                new ContextReport(false, 0, false, false, false, 0, 0,
                        false, false, false, false, Map.of(), List.of()),
                analyzer.analyze(task), 10, List.of(source), new DependencyGraph(nodes, List.of()));
        return result.relevantFiles().get(0);
    }

    private static SourceFileIndex java(String path, String packageName, String name, JavaRole role, String content) {
        return new SourceFileIndex(path, FileType.JAVA, packageName + "." + name, name, packageName, role,
                Set.of(), Set.of(), content);
    }

    private static DependencyGraph graph(List<SourceFileIndex> sources, List<DependencyEdge> edges) {
        Map<String, JavaTypeInfo> nodes = sources.stream().collect(java.util.stream.Collectors.toMap(
                SourceFileIndex::qualifiedName,
                source -> new JavaTypeInfo(source.qualifiedName(), source.className(), source.packageName(),
                        source.role(), source.path())));
        return new DependencyGraph(nodes, edges);
    }
}
