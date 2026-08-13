package io.agentforge.application;

import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeStatus;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.VerificationChangeSet;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationRequest;
import io.agentforge.policy.PolicyEngine;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.verification.CheckEngine;
import io.agentforge.verification.ExecutionArchitectureAttributor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CheckService {
    private final PlanService planService;
    private final DiffAuditService diffAuditService;
    private final ExecutionBaselineService executionBaselineService;
    private final RepositoryScanner repositoryScanner;
    private final ArchitectureScanner architectureScanner;
    private final PolicyEngine policyEngine;
    private final ExecutionArchitectureAttributor architectureAttributor;
    private final CheckEngine checkEngine;

    public CheckService(PlanService planService, DiffAuditService diffAuditService,
                        ExecutionBaselineService executionBaselineService,
                        RepositoryScanner repositoryScanner, ArchitectureScanner architectureScanner,
                        PolicyEngine policyEngine,
                        ExecutionArchitectureAttributor architectureAttributor, CheckEngine checkEngine) {
        this.planService = planService;
        this.diffAuditService = diffAuditService;
        this.executionBaselineService = executionBaselineService;
        this.repositoryScanner = repositoryScanner;
        this.architectureScanner = architectureScanner;
        this.policyEngine = policyEngine;
        this.architectureAttributor = architectureAttributor;
        this.checkEngine = checkEngine;
    }

    public VerificationReport check(VerificationRequest request) {
        if (!request.executionDeltaMode()) {
            EngineeringPlan plan = planService.plan(new EngineeringPlanRequest(request.path(), request.task()));
            return checkEngine.verify(plan, diffAuditService.audit(plan.repository().repository().path()));
        }
        return checkExecution(request);
    }

    private VerificationReport checkExecution(VerificationRequest request) {
        return checkExecution(executionBaselineService.compare(
                request.path(), request.task(), request.baselineFile()));
    }

    public VerificationReport checkExecution(ExecutionBaselineService.ComparisonResult comparison) {
        if (comparison == null) throw new IllegalArgumentException("Execution comparison is required");
        EngineeringBoundarySnapshot boundary = comparison.boundary();
        ExecutionChangeReport execution = comparison.execution();
        ArchitectureReport currentArchitecture = currentArchitecture(boundary, comparison.currentSnapshot());
        ExecutionArchitectureAttributor.Result architecture = architectureAttributor.attribute(
                currentArchitecture, execution);
        PolicyReport policy = policyEngine.evaluate(policyChanges(execution), boundary.changePolicy(),
                architecture.report());
        VerificationChangeSet facts = VerificationChangeSet.executionDelta(
                repositoryInfo(comparison.currentSnapshot()), execution, policy, architecture.report(),
                boundary.defaultPolicyUsed(), architecture.limitations());
        return checkEngine.verify(boundary, facts);
    }

    private ArchitectureReport currentArchitecture(EngineeringBoundarySnapshot boundary,
                                                    RepositorySnapshot current) {
        if (!boundary.contractConfigured()) {
            return new ArchitectureReport(new DependencyGraph(Map.of(), List.of()), Map.of(), Map.of(),
                    List.of(), List.of(), false, boundary.architectureAnalysisMode(), List.of());
        }
        Path root = Path.of(current.repositoryRoot());
        RepositoryScanner.Result repository = repositoryScanner.scan(root);
        return architectureScanner.scan(root, repository.files(), Optional.of(boundary.architectureContract()));
    }

    private RepositoryInfo repositoryInfo(RepositorySnapshot snapshot) {
        Path root = Path.of(snapshot.repositoryRoot());
        boolean dirty = snapshot.files().stream().anyMatch(file -> !"  ".equals(file.porcelainStatus()));
        return new RepositoryInfo(root, true, root, snapshot.branch(), snapshot.head(), dirty, List.of());
    }

    private List<ChangedFile> policyChanges(ExecutionChangeReport execution) {
        return execution.changes().stream().map(change -> new ChangedFile(
                switch (change.type()) {
                    case ADDED -> ChangeStatus.ADDED;
                    case MODIFIED, GIT_STATE_CHANGED -> ChangeStatus.MODIFIED;
                    case DELETED -> ChangeStatus.DELETED;
                    case RENAMED -> ChangeStatus.RENAMED;
                }, change.path(), change.oldPath())).toList();
    }
}
