package io.agentforge.inspection;

import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RelevantModule;
import io.agentforge.model.RelevantSourceArea;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.SourceFileIndex;
import io.agentforge.model.TaskAnalysis;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RelevantFileEngine {

    public Result evaluate(Path root, List<Path> repositoryFiles, RepositoryStructure structure,
                           ContextReport context, TaskAnalysis task, int limit,
                           List<SourceFileIndex> javaIndex, DependencyGraph graph) {
        return new CoreRelevantFileEvaluator().evaluate(root, repositoryFiles, structure, context,
                task, limit, javaIndex, graph);
    }

    public record Result(
            List<RelevantFile> relevantFiles,
            List<RelevantModule> relevantModules,
            List<RelevantSourceArea> relevantSourceAreas,
            List<DependencyEdge> relatedFlow,
            List<String> warnings
    ) {
        public Result {
            relevantFiles = List.copyOf(relevantFiles);
            relevantModules = List.copyOf(relevantModules);
            relevantSourceAreas = List.copyOf(relevantSourceAreas);
            relatedFlow = List.copyOf(relatedFlow);
            warnings = List.copyOf(warnings);
        }
    }
}
