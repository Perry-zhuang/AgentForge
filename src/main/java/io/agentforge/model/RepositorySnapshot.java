package io.agentforge.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@JsonPropertyOrder({"schemaVersion", "repositoryRoot", "branch", "head", "files"})
public record RepositorySnapshot(
        int schemaVersion,
        String repositoryRoot,
        String branch,
        String head,
        List<RepositoryFileState> files
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RepositorySnapshot {
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            throw new IllegalArgumentException("Snapshot repository root is required");
        }
        if (branch == null || branch.isBlank() || head == null || head.isBlank()) {
            throw new IllegalArgumentException("Snapshot branch and HEAD are required");
        }
        if (files == null) throw new IllegalArgumentException("Snapshot files are required");
        files = files.stream().sorted(Comparator.comparing(RepositoryFileState::path)).toList();
        Set<String> paths = new HashSet<>();
        for (RepositoryFileState file : files) {
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("Duplicate snapshot path: " + file.path());
            }
        }
    }

    @JsonPropertyOrder({"path", "oldPath", "tracked", "exists", "kind", "worktreeContentIdentity",
            "indexIdentity", "porcelainStatus"})
    public record RepositoryFileState(
            String path,
            String oldPath,
            boolean tracked,
            boolean exists,
            FileKind kind,
            String worktreeContentIdentity,
            String indexIdentity,
            String porcelainStatus
    ) {
        public RepositoryFileState {
            requireRelativePath(path, "Snapshot path");
            if (oldPath != null) requireRelativePath(oldPath, "Snapshot old path");
            if (kind == null) throw new IllegalArgumentException("Snapshot file kind is required: " + path);
            if (porcelainStatus == null || porcelainStatus.length() != 2) {
                throw new IllegalArgumentException("Snapshot porcelain status must contain two characters: " + path);
            }
            if (kind == FileKind.MISSING && exists
                    || (kind == FileKind.REGULAR || kind == FileKind.SYMLINK) && !exists) {
                throw new IllegalArgumentException("Snapshot existence and kind disagree: " + path);
            }
            if (worktreeContentIdentity != null
                    && !worktreeContentIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid SHA-256 content identity: " + path);
            }
        }

        private static void requireRelativePath(String path, String label) {
            if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                    || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
                throw new IllegalArgumentException(label + " must be a repository-relative path: " + path);
            }
        }
    }

    public enum FileKind {
        REGULAR,
        SYMLINK,
        GITLINK,
        MISSING
    }
}
