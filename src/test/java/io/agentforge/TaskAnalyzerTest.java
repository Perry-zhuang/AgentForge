package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.inspection.TaskAnalyzer;
import org.junit.jupiter.api.Test;

class TaskAnalyzerTest {
    private final TaskAnalyzer analyzer = new TaskAnalyzer();

    @Test
    void separatesCoreAndDerivedChineseTokensWithoutTaskVerbs() {
        var analysis = analyzer.analyze("分析备份历史告警同步");

        assertThat(analysis.coreTokens()).containsExactly("备份", "历史", "告警", "同步");
        assertThat(analysis.exactPhrases()).containsExactly("备份历史告警同步");
        assertThat(analysis.derivedTokens()).contains("备份历史", "历史告警", "告警同步")
                .doesNotContain("分析", "备份", "历史", "告警", "同步");
    }

    @Test
    void identifiesBothCoreConceptsInShortChineseTask() {
        var analysis = analyzer.analyze("历史告警");

        assertThat(analysis.coreTokens()).containsExactly("历史", "告警");
        assertThat(analysis.exactPhrases()).containsExactly("历史告警");
        assertThat(analysis.derivedTokens()).contains("历史告", "史告警", "史告");
    }

    @Test
    void splitsEnglishWordsAndCamelCaseWhilePreservingCompositeIdentifier() {
        var analysis = analyzer.analyze("login authentication SysAlertService");
        assertThat(analysis.coreTokens())
                .contains("login", "authentication", "alert")
                .doesNotContain("service", "sys");
        assertThat(analysis.exactPhrases()).contains("sysalertservice");
    }

    @Test
    void splitsSnakeCaseAndDropsShortParts() {
        var analysis = analyzer.analyze("dc_ecloud_sync");
        assertThat(analysis.coreTokens())
                .contains("ecloud", "sync")
                .doesNotContain("dc");
        assertThat(analysis.exactPhrases()).contains("dcecloudsync").doesNotContain("dc");
    }

    @Test
    void supportsMixedChineseAndEnglishWithStableDeduplication() {
        assertThat(analyzer.analyze("分析 login 登录 login").coreTokens())
                .containsExactly("login", "登录");
    }
}
