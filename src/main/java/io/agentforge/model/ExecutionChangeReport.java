package io.agentforge.model;

import java.util.Comparator;
import java.util.List;

public record ExecutionChangeReport(
        String repositoryRoot,
        String baselineBranch,
        String currentBranch,
        boolean branchChanged,
        String baselineHead,
        String currentHead,
        boolean headChanged,
        List<ExecutionChange> changes
) {
    public ExecutionChangeReport {
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            throw new IllegalArgumentException("Execution delta repository root is required");
        }
        if (baselineBranch == null || currentBranch == null || baselineHead == null || currentHead == null) {
            throw new IllegalArgumentException("Execution delta Git facts are required");
        }
        if (changes == null) throw new IllegalArgumentException("Execution delta changes are required");
        changes = changes.stream().sorted(Comparator.comparing(ExecutionChange::path)
                .thenComparing(change -> change.oldPath() == null ? "" : change.oldPath())
                .thenComparing(change -> change.type().ordinal())).toList();
    }

    public boolean hasExecutionChanges() {
        return branchChanged || headChanged || !changes.isEmpty();
    }

    public record ExecutionChange(
            ChangeType type,
            String path,
            String oldPath,
            boolean contentChanged,
            boolean gitStateChanged
    ) {
        public ExecutionChange {
            if (type == null) throw new IllegalArgumentException("Execution change type is required");
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Execution change path is required");
            if (type == ChangeType.RENAMED && (oldPath == null || oldPath.isBlank())) {
                throw new IllegalArgumentException("Rename old path is required");
            }
            if (type != ChangeType.RENAMED) oldPath = null;
        }
    }

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        GIT_STATE_CHANGED
    }
}
