package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.policy.ReadinessEngine;
import io.agentforge.scanner.ContextScanner;
import io.agentforge.scanner.ScanSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextScannerTest {
    private final ContextScanner scanner = new ContextScanner();

    @Test
    void findsAllSupportedContextLocations(@TempDir Path root) throws Exception {
        write(root, "AGENTS.md", "# Rules\nGit workflow. Database migration review. API contract compatibility. Run tests.");
        write(root, "CLAUDE.md", "# Claude rules");
        write(root, "README.md", "# Demo");
        write(root, "docs/architecture/overview.md", "# Architecture");
        write(root, "skills/review/SKILL.md", "# Review");
        write(root, ".skill/security/SKILL.md", "# Security");
        write(root, "agents/backend.md", "# Backend agent");
        write(root, ".agent/frontend.md", "# Frontend agent");

        ContextReport report = scan(root);

        assertThat(report.agentsPresent()).isTrue();
        assertThat(report.readmePresent()).isTrue();
        assertThat(report.architectureDocumentPresent()).isTrue();
        assertThat(report.skillsCount()).isEqualTo(2);
        assertThat(report.agentRulesCount()).isEqualTo(4);
        assertThat(report.gitRulesPresent()).isTrue();
        assertThat(report.databaseRulesPresent()).isTrue();
        assertThat(report.apiRulesPresent()).isTrue();
        assertThat(report.testingRulesPresent()).isTrue();
    }

    @Test
    void recognizesChineseKeywordsAndKeepsEvidencePaths(@TempDir Path root) throws Exception {
        write(root, "docs/rules.md", "分支规范和提交规范。数据库迁移规则。接口契约。运行测试。");

        ContextReport report = scan(root);

        assertThat(report.evidence()).containsKeys("git", "database", "api", "testing");
        assertThat(report.evidence().values()).allSatisfy(paths -> assertThat(paths).contains("docs/rules.md"));
    }

    @Test
    void oversizedAgentsFileProducesExplainableReadinessPenalty(@TempDir Path root) throws Exception {
        write(root, "AGENTS.md", String.join("\n", java.util.Collections.nCopies(301, "rule")));
        ContextReport context = scan(root);
        var config = new ConfigLoader().loadPolicy(root).config();
        RepositoryInfo repository = new RepositoryInfo(root, false, null, "N/A", "N/A", false, List.of());
        RepositoryStructure structure = new RepositoryStructure(List.of(), Map.of(), 0, 0, List.of());
        ArchitectureReport architecture = new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(), Map.of(),
                List.of(), List.of(), false, List.of());

        var scores = new ReadinessEngine().evaluate(root, repository, structure, context, architecture, config, false);

        assertThat(context.agentsLines()).isEqualTo(301);
        assertThat(scores.stream().filter(score -> score.name().equals("Context Quality")).findFirst().orElseThrow().details())
                .contains("-10 AGENTS.md exceeds 300 lines");
    }

    private ContextReport scan(Path root) {
        return scanner.scan(root, ScanSupport.walk(root).files());
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
