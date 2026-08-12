package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.config.ConfigLoader;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.policy.MinimalChangeEngine;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MinimalChangeEngineTest {
    private final PolicyConfig.MinimalChangePolicy policy = new ConfigLoader()
            .loadPolicy(Path.of("missing-test-root")).config().minimalChange();
    private final MinimalChangeEngine engine = new MinimalChangeEngine();

    @Test
    void noChangeScoresOneHundred() {
        var score = engine.evaluate(change(0, 0, false, false, false, false, false), policy);

        assertThat(score.score()).isEqualTo(100);
        assertThat(score.details()).containsExactly("No minimal-change penalties applied");
    }

    @Test
    void exactFreeAllowancesDoNotReduceScore() {
        assertThat(engine.evaluate(change(5, 1, false, false, false, false, false), policy).score()).isEqualTo(100);
    }

    @Test
    void firstFileAboveAllowanceUsesConfiguredPenalty() {
        assertThat(engine.evaluate(change(6, 1, false, false, false, false, false), policy).score()).isEqualTo(98);
    }

    @Test
    void fileAndModulePenaltiesStopAtTheirCaps() {
        var score = engine.evaluate(change(50, 8, false, false, false, false, false), policy);

        assertThat(score.score()).isEqualTo(46);
        assertThat(score.details()).contains("-30 Files above free allowance", "-24 Modules above free allowance");
    }

    @Test
    void combinesConfiguredSensitiveChangePenalties() {
        var score = engine.evaluate(change(1, 1, true, true, true, true, true), policy);

        assertThat(score.score()).isEqualTo(30);
        assertThat(score.details()).hasSize(5);
    }

    @Test
    void combinedPenaltiesNeverDropBelowZeroOrRiseAboveOneHundred() {
        assertThat(engine.evaluate(change(100, 10, true, true, true, true, true), policy).score()).isZero();
        assertThat(engine.evaluate(change(0, 0, false, false, false, false, false), policy).score()).isLessThanOrEqualTo(100);
    }

    private ChangeReport change(int fileCount, int moduleCount, boolean config, boolean dependency,
                                boolean database, boolean security, boolean agents) {
        List<ChangedFile> files = IntStream.range(0, fileCount)
                .mapToObj(index -> new ChangedFile(ChangeStatus.MODIFIED, "module" + index + "/File.java"))
                .toList();
        Set<String> modules = new LinkedHashSet<>();
        IntStream.range(0, moduleCount).forEach(index -> modules.add("module" + index));
        return new ChangeReport(new RepositoryInfo(Path.of("."), true, Path.of("."), "main", "abc", !files.isEmpty(), List.of()),
                files, modules, config, dependency, database, security, agents, false, List.of());
    }
}
