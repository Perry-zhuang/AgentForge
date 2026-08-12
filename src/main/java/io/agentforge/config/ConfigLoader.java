package io.agentforge.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.PolicyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ConfigLoader {
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public LoadedPolicy loadPolicy(Path root) {
        Path file = root.resolve(".agentforge/policies.yaml");
        if (!Files.exists(file)) {
            PolicyConfig defaults = read(DefaultConfigs.POLICIES_YAML, PolicyConfig.class, "built-in policies");
            validate(defaults);
            return new LoadedPolicy(defaults, true);
        }
        PolicyConfig config = read(file, PolicyConfig.class);
        validate(config);
        return new LoadedPolicy(config, false);
    }

    public Optional<ArchitectureContract> loadArchitecture(Path root) {
        Path file = root.resolve(".agentforge/architecture.yaml");
        if (!Files.exists(file)) return Optional.empty();
        ArchitectureContract contract = read(file, ArchitectureContract.class);
        if (contract.schemaVersion() != 1) {
            throw new ConfigurationException("Unsupported architecture schema_version: " + contract.schemaVersion());
        }
        if (contract.architecture() == null) {
            throw new ConfigurationException("architecture.yaml must define architecture");
        }
        if (contract.architecture().layers().isEmpty()) {
            throw new ConfigurationException("architecture.layers must declare at least one layer");
        }
        Set<String> layers = new HashSet<>(contract.architecture().layers());
        for (var entry : contract.architecture().rules().entrySet()) {
            String layer = entry.getKey();
            if (!layers.contains(layer)) {
                throw new ConfigurationException("Architecture rule references undeclared layer: " + layer);
            }
            if (entry.getValue() == null) {
                throw new ConfigurationException("Architecture rule must define allow_dependencies: " + layer);
            }
            for (String dependency : entry.getValue().allowDependencies()) {
                if (!layers.contains(dependency)) {
                    throw new ConfigurationException("Architecture rule for " + layer
                            + " allows undeclared layer: " + dependency);
                }
            }
        }
        return Optional.of(contract);
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            return mapper.readValue(Files.readString(file), type);
        } catch (IOException exception) {
            throw new ConfigurationException("Invalid configuration " + file + ": " + exception.getMessage(), exception);
        }
    }

    private <T> T read(String yaml, Class<T> type, String label) {
        try {
            return mapper.readValue(yaml, type);
        } catch (IOException exception) {
            throw new ConfigurationException("Invalid " + label + ": " + exception.getMessage(), exception);
        }
    }

    private void validate(PolicyConfig config) {
        if (config.schemaVersion() != 1) throw new ConfigurationException("Unsupported policies schema_version: " + config.schemaVersion());
        if (config.change() == null || config.minimalChange() == null || config.risk() == null || config.readiness() == null) {
            throw new ConfigurationException("policies.yaml must define change, minimal_change, risk, and readiness");
        }
        if (config.change().maxFiles() < 1) throw new ConfigurationException("change.max_files must be at least 1");
        PolicyConfig.MinimalChangePolicy minimal = config.minimalChange();
        requireNonNegative("minimal_change.free_files", minimal.freeFiles());
        requireNonNegative("minimal_change.per_extra_file", minimal.perExtraFile());
        requireNonNegative("minimal_change.max_file_penalty", minimal.maxFilePenalty());
        requireNonNegative("minimal_change.free_modules", minimal.freeModules());
        requireNonNegative("minimal_change.per_extra_module", minimal.perExtraModule());
        requireNonNegative("minimal_change.max_module_penalty", minimal.maxModulePenalty());
        requireNonNegative("minimal_change.config_change_penalty", minimal.configChangePenalty());
        requireNonNegative("minimal_change.dependency_change_penalty", minimal.dependencyChangePenalty());
        requireNonNegative("minimal_change.database_change_penalty", minimal.databaseChangePenalty());
        requireNonNegative("minimal_change.security_change_penalty", minimal.securityChangePenalty());
        requireNonNegative("minimal_change.agents_change_penalty", minimal.agentsChangePenalty());

        PolicyConfig.RiskPolicy risk = config.risk();
        if (risk.mediumThreshold() < 0
                || risk.mediumThreshold() >= risk.highThreshold()
                || risk.highThreshold() >= risk.criticalThreshold()) {
            throw new ConfigurationException("Risk thresholds must increase: medium < high < critical");
        }
        requireNonNegative("risk.max_modules", risk.maxModules());
        requireNonNegative("risk.policy_violation", risk.policyViolation());
        requireNonNegative("risk.dependency_change", risk.dependencyChange());
        requireNonNegative("risk.security_change", risk.securityChange());
        requireNonNegative("risk.database_change", risk.databaseChange());
        requireNonNegative("risk.files_over_limit", risk.filesOverLimit());
        requireNonNegative("risk.modules_over_limit", risk.modulesOverLimit());
        requireNonNegative("risk.controller_change", risk.controllerChange());
        requireNonNegative("risk.agents_change", risk.agentsChange());
        requireNonNegative("risk.config_change", risk.configChange());

        PolicyConfig.ReadinessPolicy readiness = config.readiness();
        if (readiness.context() == null || readiness.architecture() == null || readiness.changeSafety() == null
                || readiness.testability() == null || readiness.documentation() == null) {
            throw new ConfigurationException("readiness must define all five score sections");
        }
        validateReadiness(readiness);
    }

    private void validateReadiness(PolicyConfig.ReadinessPolicy readiness) {
        PolicyConfig.ContextWeights context = readiness.context();
        requireNonNegative("readiness.context.agents", context.agents());
        requireNonNegative("readiness.context.readme", context.readme());
        requireNonNegative("readiness.context.skills", context.skills());
        requireNonNegative("readiness.context.agent_rules", context.agentRules());
        requireNonNegative("readiness.context.git_rules", context.gitRules());
        requireNonNegative("readiness.context.database_rules", context.databaseRules());
        requireNonNegative("readiness.context.api_rules", context.apiRules());
        requireNonNegative("readiness.context.testing_rules", context.testingRules());
        requireNonNegative("readiness.context.agents_too_large_penalty", context.agentsTooLargePenalty());
        requireNonNegative("readiness.context.agents_max_lines", context.agentsMaxLines());

        PolicyConfig.ArchitectureWeights architecture = readiness.architecture();
        requireNonNegative("readiness.architecture.architecture_doc", architecture.architectureDoc());
        requireNonNegative("readiness.architecture.architecture_contract", architecture.architectureContract());
        requireNonNegative("readiness.architecture.recognizable_structure", architecture.recognizableStructure());
        requireNonNegative("readiness.architecture.no_layer_bypass", architecture.noLayerBypass());

        PolicyConfig.ChangeSafetyWeights safety = readiness.changeSafety();
        requireNonNegative("readiness.change_safety.policies", safety.policies());
        requireNonNegative("readiness.change_safety.gitignore", safety.gitignore());
        requireNonNegative("readiness.change_safety.git_repository", safety.gitRepository());
        requireNonNegative("readiness.change_safety.git_rules", safety.gitRules());
        requireNonNegative("readiness.change_safety.database_rules", safety.databaseRules());
        requireNonNegative("readiness.change_safety.api_rules", safety.apiRules());

        PolicyConfig.TestabilityWeights testability = readiness.testability();
        requireNonNegative("readiness.testability.tests_present", testability.testsPresent());
        requireNonNegative("readiness.testability.test_ratio", testability.testRatio());
        requireNonNegative("readiness.testability.testing_rules", testability.testingRules());
        if (testability.minimumTestRatio() < 0 || testability.minimumTestRatio() > 1) {
            throw new ConfigurationException("readiness.testability.minimum_test_ratio must be between 0 and 1");
        }

        PolicyConfig.DocumentationWeights documentation = readiness.documentation();
        requireNonNegative("readiness.documentation.readme", documentation.readme());
        requireNonNegative("readiness.documentation.architecture_doc", documentation.architectureDoc());
        requireNonNegative("readiness.documentation.agents", documentation.agents());
        requireNonNegative("readiness.documentation.docs_directory", documentation.docsDirectory());
    }

    private void requireNonNegative(String field, int value) {
        if (value < 0) throw new ConfigurationException(field + " cannot be negative");
    }

    public record LoadedPolicy(PolicyConfig config, boolean defaultUsed) {}
}
