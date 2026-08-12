package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentforge.config.ConfigLoader;
import io.agentforge.config.ConfigurationException;
import io.agentforge.config.DefaultConfigs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void usesBuiltInDefaultsOnlyWhenPolicyFileIsMissing(@TempDir Path root) {
        ConfigLoader.LoadedPolicy loaded = loader.loadPolicy(root);

        assertThat(loaded.defaultUsed()).isTrue();
        assertThat(loaded.config().change().maxFiles()).isEqualTo(20);
    }

    @Test
    void rejectsUnknownFieldsInsteadOfSilentlyUsingDefaults(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML + "unknown: true\n");

        assertInvalidPolicy(root, "unknown");
    }

    @Test
    void rejectsUnsupportedSchemaVersion(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replaceFirst("schema_version: 1", "schema_version: 2"));

        assertInvalidPolicy(root, "schema_version");
    }

    @Test
    void rejectsMissingRequiredSection(@TempDir Path root) throws Exception {
        String yaml = DefaultConfigs.POLICIES_YAML.substring(0, DefaultConfigs.POLICIES_YAML.indexOf("readiness:"));
        writePolicy(root, yaml);

        assertInvalidPolicy(root, "must define change, minimal_change, risk, and readiness");
    }

    @Test
    void rejectsInvalidMaxFiles(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("max_files: 20", "max_files: 0"));

        assertInvalidPolicy(root, "change.max_files");
    }

    @Test
    void rejectsInvalidRiskThresholds(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("high_threshold: 40", "high_threshold: 20"));

        assertInvalidPolicy(root, "Risk thresholds");
    }

    @Test
    void rejectsNegativePenalty(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("security_change_penalty: 20", "security_change_penalty: -1"));

        assertInvalidPolicy(root, "minimal_change.security_change_penalty");
    }

    @Test
    void rejectsNegativeRiskWeight(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("policy_violation: 30", "policy_violation: -1"));

        assertInvalidPolicy(root, "risk.policy_violation");
    }

    @Test
    void rejectsInvalidReadinessWeightAndRatio(@TempDir Path root) throws Exception {
        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("agents: 35", "agents: -1"));
        assertInvalidPolicy(root, "readiness.context.agents");

        writePolicy(root, DefaultConfigs.POLICIES_YAML.replace("minimum_test_ratio: 0.10", "minimum_test_ratio: 1.1"));
        assertInvalidPolicy(root, "minimum_test_ratio");
    }

    @Test
    void rejectsArchitectureRulesForUndeclaredSourceOrTargetLayers(@TempDir Path root) throws Exception {
        writeArchitecture(root, """
                schema_version: 1
                architecture:
                  layers: [controller, service]
                  rules:
                    mapper:
                      allow_dependencies: [service]
                """);
        assertThatThrownBy(() -> loader.loadArchitecture(root))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("undeclared layer: mapper");

        writeArchitecture(root, """
                schema_version: 1
                architecture:
                  layers: [controller, service]
                  rules:
                    controller:
                      allow_dependencies: [mapper]
                """);
        assertThatThrownBy(() -> loader.loadArchitecture(root))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("allows undeclared layer: mapper");
    }

    private void assertInvalidPolicy(Path root, String message) {
        assertThatThrownBy(() -> loader.loadPolicy(root))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(message);
    }

    private static void writePolicy(Path root, String yaml) throws Exception {
        Files.createDirectories(root.resolve(".agentforge"));
        Files.writeString(root.resolve(".agentforge/policies.yaml"), yaml);
    }

    private static void writeArchitecture(Path root, String yaml) throws Exception {
        Files.createDirectories(root.resolve(".agentforge"));
        Files.writeString(root.resolve(".agentforge/architecture.yaml"), yaml);
    }
}
