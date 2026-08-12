package io.agentforge.model;

public record GitBaseline(
        boolean available,
        String branch,
        String head,
        WorkingTreeState workingTree,
        int changedFiles
) {
    public GitBaseline {
        if (branch == null || head == null) throw new IllegalArgumentException("Git baseline values are required");
        if (workingTree == null) throw new IllegalArgumentException("Working tree state is required");
        if (changedFiles < 0) throw new IllegalArgumentException("Changed file count cannot be negative");
    }

    public enum WorkingTreeState { CLEAN, DIRTY, NOT_AVAILABLE }

    public static GitBaseline unavailable() {
        return new GitBaseline(false, "N/A", "N/A", WorkingTreeState.NOT_AVAILABLE, 0);
    }
}
