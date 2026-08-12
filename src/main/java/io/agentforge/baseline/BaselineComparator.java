package io.agentforge.baseline;

import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.ExecutionChangeReport.ChangeType;
import io.agentforge.model.ExecutionChangeReport.ExecutionChange;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.RepositorySnapshot.RepositoryFileState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class BaselineComparator {
    public ExecutionChangeReport compare(RepositorySnapshot before, RepositorySnapshot after) {
        validate(before, after);
        Map<String, RepositoryFileState> beforeByPath = byPath(before.files());
        Map<String, RepositoryFileState> afterByPath = byPath(after.files());
        Set<String> consumedBefore = new LinkedHashSet<>();
        Set<String> consumedAfter = new LinkedHashSet<>();
        List<ExecutionChange> changes = new ArrayList<>();

        addNewRenames(beforeByPath, afterByPath, consumedBefore, consumedAfter, changes);
        Set<String> paths = new TreeSet<>();
        paths.addAll(beforeByPath.keySet());
        paths.addAll(afterByPath.keySet());
        for (String path : paths) {
            if (consumedBefore.contains(path) || consumedAfter.contains(path)) continue;
            addPathChange(path, beforeByPath.get(path), afterByPath.get(path), changes);
        }

        boolean branchChanged = !before.branch().equals(after.branch());
        boolean headChanged = !before.head().equals(after.head());
        return new ExecutionChangeReport(before.repositoryRoot(), before.branch(), after.branch(), branchChanged,
                before.head(), after.head(), headChanged, changes);
    }

    private void validate(RepositorySnapshot before, RepositorySnapshot after) {
        requireCurrentSchema(before);
        requireCurrentSchema(after);
        if (!before.repositoryRoot().equals(after.repositoryRoot())) {
            throw new IllegalArgumentException("Baseline repository does not match current repository: "
                    + before.repositoryRoot() + " != " + after.repositoryRoot());
        }
    }

    private void requireCurrentSchema(RepositorySnapshot snapshot) {
        if (snapshot.schemaVersion() != RepositorySnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported baseline schema version: " + snapshot.schemaVersion());
        }
    }

    private Map<String, RepositoryFileState> byPath(List<RepositoryFileState> files) {
        Map<String, RepositoryFileState> result = new LinkedHashMap<>();
        files.forEach(file -> result.put(file.path(), file));
        return result;
    }

    private void addNewRenames(Map<String, RepositoryFileState> beforeByPath,
                               Map<String, RepositoryFileState> afterByPath,
                               Set<String> consumedBefore, Set<String> consumedAfter,
                               List<ExecutionChange> changes) {
        for (RepositoryFileState after : afterByPath.values()) {
            if (!renameFact(after) || after.oldPath() == null) continue;
            RepositoryFileState samePathBefore = beforeByPath.get(after.path());
            if (samePathBefore != null && Objects.equals(samePathBefore.oldPath(), after.oldPath())) {
                continue;
            }
            RepositoryFileState oldPathBefore = beforeByPath.get(after.oldPath());
            if (oldPathBefore == null || consumedBefore.contains(oldPathBefore.path())) continue;
            changes.add(new ExecutionChange(ChangeType.RENAMED, after.path(), after.oldPath(),
                    contentChanged(oldPathBefore, after), gitStateChanged(oldPathBefore, after)));
            consumedBefore.add(oldPathBefore.path());
            consumedAfter.add(after.path());
        }
    }

    private boolean renameFact(RepositoryFileState state) {
        return state.porcelainStatus().indexOf('R') >= 0;
    }

    private void addPathChange(String path, RepositoryFileState before, RepositoryFileState after,
                               List<ExecutionChange> changes) {
        if (before == null) {
            if (after.exists()) {
                changes.add(new ExecutionChange(ChangeType.ADDED, path, null, true, true));
            } else {
                changes.add(new ExecutionChange(ChangeType.GIT_STATE_CHANGED, path, null, false, true));
            }
            return;
        }
        if (after == null) {
            if (before.exists()) {
                changes.add(new ExecutionChange(ChangeType.DELETED, path, null, true, true));
            } else {
                changes.add(new ExecutionChange(ChangeType.GIT_STATE_CHANGED, path, null, false, true));
            }
            return;
        }

        boolean contentChanged = contentChanged(before, after);
        boolean gitStateChanged = gitStateChanged(before, after);
        if (!before.exists() && after.exists()) {
            changes.add(new ExecutionChange(ChangeType.ADDED, path, null, true, gitStateChanged));
        } else if (before.exists() && !after.exists()) {
            changes.add(new ExecutionChange(ChangeType.DELETED, path, null, true, gitStateChanged));
        } else if (contentChanged) {
            changes.add(new ExecutionChange(ChangeType.MODIFIED, path, null, true, gitStateChanged));
        } else if (gitStateChanged) {
            changes.add(new ExecutionChange(ChangeType.GIT_STATE_CHANGED, path, null, false, true));
        }
    }

    private boolean contentChanged(RepositoryFileState before, RepositoryFileState after) {
        return before.exists() != after.exists()
                || before.kind() != after.kind()
                || !Objects.equals(before.worktreeContentIdentity(), after.worktreeContentIdentity());
    }

    private boolean gitStateChanged(RepositoryFileState before, RepositoryFileState after) {
        return before.tracked() != after.tracked()
                || !Objects.equals(before.indexIdentity(), after.indexIdentity())
                || !before.porcelainStatus().equals(after.porcelainStatus())
                || !Objects.equals(before.oldPath(), after.oldPath());
    }
}
