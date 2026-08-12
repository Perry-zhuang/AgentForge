package io.agentforge.planning;

import io.agentforge.model.BuildSystem;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.FileResponsibility;
import io.agentforge.model.JavaRole;
import io.agentforge.model.PlanScope;
import io.agentforge.model.PlanTest;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RepositoryReport;
import io.agentforge.model.TaskType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PlanTestAdvisor {
    private static final List<String> SCHEDULE_TERMS = List.of("task", "job", "scheduler", "quartz");

    public List<PlanTest> advise(RepositoryReport repository, PlanScope scope,
                                 List<DependencyEdge> relatedFlow, TaskType taskType,
                                 boolean reliableScope, double minimumTestRatio) {
        if (!reliableScope) {
            return List.of(
                    new PlanTest("Refine the task and rerun agentforge inspect before defining implementation tests."),
                    new PlanTest("Do not treat LOW or REFERENCE files as an implementation test target."));
        }

        LinkedHashSet<String> actions = new LinkedHashSet<>();
        addRepositoryTests(actions, repository);
        boolean limited = limitedTests(repository, minimumTestRatio);
        if (taskType == TaskType.BUGFIX) {
            actions.add(limited
                    ? "Add a regression test when practical; otherwise reproduce and verify the failure manually."
                    : "Add or run a regression test covering the reproduced failure path.");
        }

        List<RelevantFile> candidates = new ArrayList<>(scope.primary());
        candidates.addAll(scope.secondary());
        if (candidates.stream().anyMatch(this::scheduledOrTaskFile)) {
            actions.add("Verify scheduled/task execution path and trigger behavior.");
        }
        Set<String> candidatePaths = new HashSet<>(candidates.stream().map(RelevantFile::path).toList());
        boolean controller = candidates.stream()
                .anyMatch(file -> file.responsibility() == FileResponsibility.CONTROLLER)
                || hasArchitectureRole(repository, candidatePaths, JavaRole.CONTROLLER);
        if (controller) actions.add("Verify affected API request/response behavior.");
        if (hasArchitectureRole(repository, candidatePaths, JavaRole.MAPPER, JavaRole.REPOSITORY)) {
            actions.add("Verify persistence reads/writes and transaction behavior.");
        }
        if (taskType == TaskType.INTEGRATION || !relatedFlow.isEmpty()) {
            actions.add("Verify upstream and downstream integration contracts.");
        }
        if (limited) {
            actions.add("Perform manual regression verification because repository test coverage is limited.");
        }
        actions.add("Run agentforge diff after implementation.");
        return actions.stream().map(PlanTest::new).toList();
    }

    public boolean limitedTests(RepositoryReport repository, double minimumTestRatio) {
        return repository.structure().testFiles() == 0
                || repository.structure().testFileRatio() < minimumTestRatio;
    }

    private void addRepositoryTests(Set<String> actions, RepositoryReport repository) {
        Set<BuildSystem> builds = repository.technology().buildSystems();
        if (builds.contains(BuildSystem.MAVEN)) actions.add("Run Maven tests.");
        if (builds.contains(BuildSystem.GRADLE)) actions.add("Run Gradle tests.");
        if (builds.contains(BuildSystem.NODE)) actions.add("Run the project test command.");
        if (builds.isEmpty()) actions.add("Run repository-native tests if available.");
    }

    private boolean scheduledOrTaskFile(RelevantFile file) {
        String name = file.className().orElseGet(() -> file.path()
                .substring(file.path().lastIndexOf('/') + 1)).toLowerCase(Locale.ROOT);
        String path = file.path().replace('\\', '/').toLowerCase(Locale.ROOT);
        return SCHEDULE_TERMS.stream().anyMatch(term -> name.contains(term) || path.contains(term));
    }

    private boolean hasArchitectureRole(RepositoryReport repository, Set<String> paths, JavaRole... roles) {
        Set<JavaRole> accepted = Set.of(roles);
        return repository.architecture().graph().nodes().values().stream()
                .anyMatch(node -> paths.contains(node.path()) && accepted.contains(node.role()));
    }
}
