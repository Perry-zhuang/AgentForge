package io.agentforge.application;

import io.agentforge.baseline.BaselineComparator;
import io.agentforge.baseline.RepositorySnapshotStore;
import io.agentforge.git.RepositoryStateCapture;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.RepositorySnapshot;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class BaselineService {
    private final RepositoryStateCapture stateCapture;
    private final RepositorySnapshotStore snapshotStore;
    private final BaselineComparator comparator;

    public BaselineService(RepositoryStateCapture stateCapture, RepositorySnapshotStore snapshotStore,
                           BaselineComparator comparator) {
        this.stateCapture = stateCapture;
        this.snapshotStore = snapshotStore;
        this.comparator = comparator;
    }

    public CaptureResult capture(Path repository, Path output, boolean force) {
        RepositorySnapshot snapshot = stateCapture.capture(repository);
        Path destination = snapshotStore.write(snapshot, output, force);
        return new CaptureResult(snapshot, destination);
    }

    public ExecutionChangeReport compare(Path repository, Path baselineFile) {
        RepositorySnapshot baseline = snapshotStore.readRepository(baselineFile);
        RepositorySnapshot current = stateCapture.capture(repository);
        snapshotStore.requireOutsideRepository(current.repositoryRoot(), baselineFile);
        return comparator.compare(baseline, current);
    }

    public record CaptureResult(RepositorySnapshot snapshot, Path destination) {}
}
