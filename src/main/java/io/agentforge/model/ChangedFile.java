package io.agentforge.model;

public record ChangedFile(ChangeStatus status, String path, String oldPath) {
    public ChangedFile(ChangeStatus status, String path) {
        this(status, path, null);
    }
}
