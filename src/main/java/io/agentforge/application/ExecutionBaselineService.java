package io.agentforge.application;

import io.agentforge.baseline.BaselineComparator;
import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.config.ConfigLoader;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.ExecutionBaseline;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.RepositorySnapshot;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ExecutionBaselineService {
    private final PlanService planService;
    private final ConfigLoader configLoader;
    private final RepositoryStateCapture stateCapture;
    private final RepositorySnapshotStore snapshotStore;
    private final BaselineComparator comparator;

    public ExecutionBaselineService(PlanService planService, ConfigLoader configLoader,
                                    RepositoryStateCapture stateCapture, RepositorySnapshotStore snapshotStore,
                                    BaselineComparator comparator) {
        this.planService = planService;
        this.configLoader = configLoader;
        this.stateCapture = stateCapture;
        this.snapshotStore = snapshotStore;
        this.comparator = comparator;
    }

    public CaptureResult capture(Path repository, String task, Path output, boolean force) {
        EngineeringPlan plan = planService.plan(new EngineeringPlanRequest(repository, task));
        ExecutionBaseline baseline = capture(plan);
        Path destination = snapshotStore.writeExecution(baseline, output, force);
        return new CaptureResult(baseline, destination);
    }

    public ExecutionBaseline capture(EngineeringPlan plan) {
        if (plan == null) throw new IllegalArgumentException("Engineering plan is required for baseline capture");
        Path root = plan.repository().repository().path();
        ConfigLoader.LoadedPolicy policy = configLoader.loadPolicy(root);
        var architecture = configLoader.loadArchitecture(root);
        requireStableConfigState(plan, policy, architecture);
        EngineeringBoundarySnapshot boundary = EngineeringBoundarySnapshot.capture(plan, policy.config().change(),
                policy.defaultUsed(), architecture);
        RepositorySnapshot snapshot = stateCapture.capture(root);
        return new ExecutionBaseline(
                ExecutionBaseline.CURRENT_SCHEMA_VERSION, snapshot, boundary);
    }

    public ComparisonResult compare(Path repository, String task, Path baselineFile) {
        ExecutionBaseline baseline = snapshotStore.readExecution(baselineFile);
        RepositorySnapshot current = stateCapture.capture(repository);
        snapshotStore.requireOutsideRepository(current.repositoryRoot(), baselineFile);
        return compare(task, baseline, current);
    }

    public ComparisonResult compare(Path repository, String task, ExecutionBaseline baseline) {
        if (baseline == null) throw new IllegalArgumentException("Execution baseline is required");
        RepositorySnapshot current = stateCapture.capture(repository);
        return compare(task, baseline, current);
    }

    private ComparisonResult compare(String task, ExecutionBaseline baseline, RepositorySnapshot current) {
        if (!baseline.engineeringBoundary().matchesTask(task)) {
            throw new IllegalArgumentException("Baseline task does not match requested task");
        }
        ExecutionChangeReport report = comparator.compare(baseline.repositorySnapshot(), current);
        return new ComparisonResult(baseline.engineeringBoundary(), current, report);
    }

    private void requireStableConfigState(EngineeringPlan plan, ConfigLoader.LoadedPolicy policy,
                                          Optional<io.agentforge.model.ArchitectureContract> architecture) {
        if (plan.defaultPolicyUsed() != policy.defaultUsed()
                || plan.repository().architecture().contractConfigured() != architecture.isPresent()) {
            throw new IllegalStateException("Engineering boundary configuration changed while baseline was captured");
        }
    }

    public record CaptureResult(ExecutionBaseline baseline, Path destination) {}

    public record ComparisonResult(
            EngineeringBoundarySnapshot boundary,
            RepositorySnapshot currentSnapshot,
            ExecutionChangeReport execution
    ) {
        public ComparisonResult {
            if (boundary == null || currentSnapshot == null || execution == null) {
                throw new IllegalArgumentException("Execution comparison facts are required");
            }
        }
    }
}
