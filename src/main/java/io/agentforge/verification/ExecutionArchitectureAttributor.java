package io.agentforge.verification;

import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.FileType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ExecutionArchitectureAttributor {
    private static final String ATTRIBUTION_LIMITATION =
            "Architecture execution attribution is limited to violations whose source file was added "
                    + "during this execution; other current violations cannot be attributed without an architecture baseline.";

    public Result attribute(ArchitectureReport current, ExecutionChangeReport execution) {
        if (current == null || execution == null) {
            throw new IllegalArgumentException("Current architecture and execution delta facts are required");
        }
        if (!current.contractConfigured()) {
            return new Result(withViolations(current, List.of(), List.of()), List.of());
        }

        Set<String> addedPaths = new LinkedHashSet<>();
        boolean architectureRelevantContentChanged = false;
        for (ExecutionChangeReport.ExecutionChange change : execution.changes()) {
            if (!change.contentChanged()) continue;
            if (javaPath(change.path()) || change.oldPath() != null && javaPath(change.oldPath())
                    || architectureContractPath(change.path())
                    || change.oldPath() != null && architectureContractPath(change.oldPath())) {
                architectureRelevantContentChanged = true;
            }
            if (change.type() == ExecutionChangeReport.ChangeType.ADDED) {
                addedPaths.add(change.path());
            }
        }

        List<DependencyEdge> introducedByAddedSources = current.contractViolations().stream()
                .filter(edge -> addedPaths.contains(sourcePath(current, edge)))
                .toList();
        List<DependencyEdge> addedSourceBypasses = current.possibleLayerBypasses().stream()
                .filter(edge -> addedPaths.contains(sourcePath(current, edge)))
                .toList();
        boolean unattributedCurrentViolations = current.contractViolations().stream()
                .anyMatch(edge -> !addedPaths.contains(sourcePath(current, edge)));
        List<String> limitations = architectureRelevantContentChanged && unattributedCurrentViolations
                ? List.of(ATTRIBUTION_LIMITATION) : List.of();
        return new Result(withViolations(current, addedSourceBypasses, introducedByAddedSources), limitations);
    }

    private ArchitectureReport withViolations(ArchitectureReport source, List<DependencyEdge> bypasses,
                                               List<DependencyEdge> violations) {
        return new ArchitectureReport(source.graph(), source.roleCounts(), source.relationCounts(),
                bypasses, violations, source.contractConfigured(), source.analysisMode(), source.warnings());
    }

    private String sourcePath(ArchitectureReport report, DependencyEdge edge) {
        return report.graph().nodes().containsKey(edge.from())
                ? report.graph().nodes().get(edge.from()).path() : "";
    }

    private boolean javaPath(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return FileType.classify(name) == FileType.JAVA;
    }

    private boolean architectureContractPath(String path) {
        return ".agentforge/architecture.yaml".equals(path.replace('\\', '/'));
    }

    public record Result(ArchitectureReport report, List<String> limitations) {
        public Result {
            if (report == null || limitations == null) {
                throw new IllegalArgumentException("Architecture attribution result is required");
            }
            limitations = List.copyOf(limitations);
        }
    }
}
