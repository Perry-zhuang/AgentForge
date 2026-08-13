package io.agentforge.application;

import io.agentforge.config.ConfigLoader;
import io.agentforge.git.GitChangeScanner;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPlanRequest;
import io.agentforge.model.TaskInspectionReport;
import io.agentforge.model.TaskInspectionRequest;
import io.agentforge.planning.PlanEngine;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PlanService {
    private static final int INSPECT_LIMIT = 10;
    private final InspectService inspectService;
    private final ConfigLoader configLoader;
    private final GitChangeScanner gitChangeScanner;
    private final PlanEngine planEngine;

    public PlanService(InspectService inspectService, ConfigLoader configLoader,
                       GitChangeScanner gitChangeScanner, PlanEngine planEngine) {
        this.inspectService = inspectService;
        this.configLoader = configLoader;
        this.gitChangeScanner = gitChangeScanner;
        this.planEngine = planEngine;
    }

    public EngineeringPlan plan(EngineeringPlanRequest request) {
        TaskInspectionReport inspection = inspectService.inspect(
                new TaskInspectionRequest(request.path(), request.task(), INSPECT_LIMIT));
        Path root = inspection.request().path();
        ConfigLoader.LoadedPolicy policy = configLoader.loadPolicy(root);
        Optional<ArchitectureContract> contract = configLoader.loadArchitecture(root);
        Optional<ChangeReport> changes = inspection.repositoryReport().repository().gitRepository()
                ? Optional.of(gitChangeScanner.scan(root)) : Optional.empty();
        return planEngine.create(inspection, policy.config(), policy.defaultUsed(), contract, changes,
                request.taskTypeOverride());
    }
}
