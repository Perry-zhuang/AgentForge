package io.agentforge.runtrace;

import java.nio.file.Path;

public record RunTraceReference(String runId, Path archiveDirectory, Path reportFile, Path exportedReport) {
    public RunTraceReference {
        if (runId == null || runId.isBlank() || archiveDirectory == null || reportFile == null) {
            throw new IllegalArgumentException("Run trace reference facts are required");
        }
    }

    public RunTraceReference withExport(Path export) {
        return new RunTraceReference(runId, archiveDirectory, reportFile, export);
    }
}
