package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentforge.baseline.BaselineComparator;
import io.agentforge.model.ExecutionChangeReport.ChangeType;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.RepositorySnapshot.FileKind;
import io.agentforge.model.RepositorySnapshot.RepositoryFileState;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaselineComparatorTest {
    private final BaselineComparator comparator = new BaselineComparator();

    @Test
    void cleanOrDirtyBeforeUnchangedProducesNoExecutionDelta() {
        RepositoryFileState clean = state("Clean.java", null, true, true, hash('a'), "100644:a:0", "  ");
        RepositoryFileState dirty = state("Dirty.java", null, true, true, hash('b'), "100644:a:0", " M");
        RepositorySnapshot before = snapshot("main", "head-a", clean, dirty);

        var report = comparator.compare(before, snapshot("main", "head-a", clean, dirty));

        assertThat(report.hasExecutionChanges()).isFalse();
        assertThat(report.changes()).isEmpty();
    }

    @Test
    void dirtyFileModifiedAgainIsDetectedEvenWhenPorcelainStateIsUnchanged() {
        RepositoryFileState before = state("Dirty.java", null, true, true, hash('a'), "100644:i:0", " M");
        RepositoryFileState after = state("Dirty.java", null, true, true, hash('b'), "100644:i:0", " M");

        var report = comparator.compare(snapshot("main", "head", before), snapshot("main", "head", after));

        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.MODIFIED);
            assertThat(change.contentChanged()).isTrue();
            assertThat(change.gitStateChanged()).isFalse();
        });
    }

    @Test
    void untrackedFilesSupportUnchangedModifiedAddedAndDeletedDeltas() {
        RepositoryFileState unchanged = untracked("unchanged.txt", hash('a'));
        RepositoryFileState modifiedBefore = untracked("modified.txt", hash('a'));
        RepositoryFileState modifiedAfter = untracked("modified.txt", hash('b'));
        RepositoryFileState deleted = untracked("deleted.txt", hash('c'));
        RepositoryFileState added = untracked("added.txt", hash('d'));

        var report = comparator.compare(snapshot("main", "head", unchanged, modifiedBefore, deleted),
                snapshot("main", "head", unchanged, modifiedAfter, added));

        assertThat(report.changes()).extracting(change -> change.type() + ":" + change.path())
                .containsExactly("ADDED:added.txt", "DELETED:deleted.txt", "MODIFIED:modified.txt");
    }

    @Test
    void preExistingRenameUnchangedProducesNoneAndModifiedAgainProducesModified() {
        RepositoryFileState renamed = state("New.java", "Old.java", true, true,
                hash('a'), "100644:a:0", "R ");
        RepositorySnapshot baseline = snapshot("main", "head", renamed);

        assertThat(comparator.compare(baseline, snapshot("main", "head", renamed)).changes()).isEmpty();

        RepositoryFileState modified = state("New.java", "Old.java", true, true,
                hash('b'), "100644:a:0", "R ");
        assertThat(comparator.compare(baseline, snapshot("main", "head", modified)).changes())
                .singleElement().satisfies(change -> {
                    assertThat(change.type()).isEqualTo(ChangeType.MODIFIED);
                    assertThat(change.path()).isEqualTo("New.java");
                    assertThat(change.oldPath()).isNull();
                });
    }

    @Test
    void newlyIntroducedGitRenameProducesRenameDelta() {
        RepositoryFileState original = state("Old.java", null, true, true,
                hash('a'), "100644:a:0", "  ");
        RepositoryFileState renamed = state("New.java", "Old.java", true, true,
                hash('a'), "100644:a:0", "R ");

        var report = comparator.compare(snapshot("main", "head", original),
                snapshot("main", "head", renamed));

        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.RENAMED);
            assertThat(change.oldPath()).isEqualTo("Old.java");
            assertThat(change.path()).isEqualTo("New.java");
            assertThat(change.contentChanged()).isFalse();
            assertThat(change.gitStateChanged()).isTrue();
        });
    }

    @Test
    void stagingOnlyChangeKeepsContentAndGitStateFactsIndependent() {
        RepositoryFileState unstaged = state("Foo.java", null, true, true,
                hash('b'), "100644:a:0", " M");
        RepositoryFileState staged = state("Foo.java", null, true, true,
                hash('b'), "100644:b:0", "M ");

        var report = comparator.compare(snapshot("main", "head", unstaged),
                snapshot("main", "head", staged));

        assertThat(report.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.GIT_STATE_CHANGED);
            assertThat(change.contentChanged()).isFalse();
            assertThat(change.gitStateChanged()).isTrue();
        });
    }

    @Test
    void branchAndHeadChangesRemainReportFacts() {
        RepositoryFileState file = state("Foo.java", null, true, true,
                hash('a'), "100644:a:0", "  ");

        var report = comparator.compare(snapshot("main", "head-a", file),
                snapshot("feature", "head-b", file));

        assertThat(report.branchChanged()).isTrue();
        assertThat(report.headChanged()).isTrue();
        assertThat(report.changes()).isEmpty();
        assertThat(report.hasExecutionChanges()).isTrue();
    }

    @Test
    void repositoryAndSchemaMismatchFailFastButOrderingIsStable() {
        RepositoryFileState z = untracked("z.txt", hash('f'));
        RepositoryFileState a = untracked("a.txt", hash('a'));
        var ordered = comparator.compare(snapshot("main", "head"), snapshot("main", "head", z, a));
        assertThat(ordered.changes()).extracting(change -> change.path()).containsExactly("a.txt", "z.txt");

        RepositorySnapshot otherRoot = new RepositorySnapshot(1, "/other", "main", "head", List.of());
        assertThatThrownBy(() -> comparator.compare(snapshot("main", "head"), otherRoot))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");

        RepositorySnapshot unknownSchema = new RepositorySnapshot(2, "/repo", "main", "head", List.of());
        assertThatThrownBy(() -> comparator.compare(unknownSchema, snapshot("main", "head")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema version");
    }

    private RepositorySnapshot snapshot(String branch, String head, RepositoryFileState... files) {
        return new RepositorySnapshot(1, "/repo", branch, head, List.of(files));
    }

    private RepositoryFileState untracked(String path, String hash) {
        return state(path, null, false, true, hash, null, "??");
    }

    private RepositoryFileState state(String path, String oldPath, boolean tracked, boolean exists,
                                      String hash, String indexIdentity, String status) {
        return new RepositoryFileState(path, oldPath, tracked, exists,
                exists ? FileKind.REGULAR : FileKind.MISSING, hash, indexIdentity, status);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
