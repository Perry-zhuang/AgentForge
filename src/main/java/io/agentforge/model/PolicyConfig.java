package io.agentforge.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PolicyConfig(
        @JsonProperty("schema_version") int schemaVersion,
        ChangePolicy change,
        @JsonProperty("minimal_change") MinimalChangePolicy minimalChange,
        RiskPolicy risk,
        ReadinessPolicy readiness
) {
    public record ChangePolicy(
            @JsonProperty("max_files") int maxFiles,
            List<String> deny,
            List<String> protectedPaths
    ) {
        public ChangePolicy(
                @JsonProperty("max_files") int maxFiles,
                List<String> deny,
                @JsonProperty("protected") List<String> protectedPaths
        ) {
            this.maxFiles = maxFiles;
            this.deny = deny == null ? List.of() : List.copyOf(deny);
            this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        }
    }

    public record MinimalChangePolicy(
            @JsonProperty("free_files") int freeFiles,
            @JsonProperty("per_extra_file") int perExtraFile,
            @JsonProperty("max_file_penalty") int maxFilePenalty,
            @JsonProperty("free_modules") int freeModules,
            @JsonProperty("per_extra_module") int perExtraModule,
            @JsonProperty("max_module_penalty") int maxModulePenalty,
            @JsonProperty("config_change_penalty") int configChangePenalty,
            @JsonProperty("dependency_change_penalty") int dependencyChangePenalty,
            @JsonProperty("database_change_penalty") int databaseChangePenalty,
            @JsonProperty("security_change_penalty") int securityChangePenalty,
            @JsonProperty("agents_change_penalty") int agentsChangePenalty
    ) {}

    public record RiskPolicy(
            @JsonProperty("medium_threshold") int mediumThreshold,
            @JsonProperty("high_threshold") int highThreshold,
            @JsonProperty("critical_threshold") int criticalThreshold,
            @JsonProperty("max_modules") int maxModules,
            @JsonProperty("policy_violation") int policyViolation,
            @JsonProperty("dependency_change") int dependencyChange,
            @JsonProperty("security_change") int securityChange,
            @JsonProperty("database_change") int databaseChange,
            @JsonProperty("files_over_limit") int filesOverLimit,
            @JsonProperty("modules_over_limit") int modulesOverLimit,
            @JsonProperty("controller_change") int controllerChange,
            @JsonProperty("agents_change") int agentsChange,
            @JsonProperty("config_change") int configChange
    ) {}

    public record ReadinessPolicy(
            ContextWeights context,
            ArchitectureWeights architecture,
            @JsonProperty("change_safety") ChangeSafetyWeights changeSafety,
            TestabilityWeights testability,
            DocumentationWeights documentation
    ) {}

    public record ContextWeights(
            int agents, int readme, int skills,
            @JsonProperty("agent_rules") int agentRules,
            @JsonProperty("git_rules") int gitRules,
            @JsonProperty("database_rules") int databaseRules,
            @JsonProperty("api_rules") int apiRules,
            @JsonProperty("testing_rules") int testingRules,
            @JsonProperty("agents_too_large_penalty") int agentsTooLargePenalty,
            @JsonProperty("agents_max_lines") int agentsMaxLines
    ) {}

    public record ArchitectureWeights(
            @JsonProperty("architecture_doc") int architectureDoc,
            @JsonProperty("architecture_contract") int architectureContract,
            @JsonProperty("recognizable_structure") int recognizableStructure,
            @JsonProperty("no_layer_bypass") int noLayerBypass
    ) {}

    public record ChangeSafetyWeights(
            int policies, int gitignore,
            @JsonProperty("git_repository") int gitRepository,
            @JsonProperty("git_rules") int gitRules,
            @JsonProperty("database_rules") int databaseRules,
            @JsonProperty("api_rules") int apiRules
    ) {}

    public record TestabilityWeights(
            @JsonProperty("tests_present") int testsPresent,
            @JsonProperty("test_ratio") int testRatio,
            @JsonProperty("testing_rules") int testingRules,
            @JsonProperty("minimum_test_ratio") double minimumTestRatio
    ) {}

    public record DocumentationWeights(
            int readme,
            @JsonProperty("architecture_doc") int architectureDoc,
            int agents,
            @JsonProperty("docs_directory") int docsDirectory
    ) {}
}
