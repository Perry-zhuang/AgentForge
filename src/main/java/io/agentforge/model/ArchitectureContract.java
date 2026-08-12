package io.agentforge.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ArchitectureContract(
        @JsonProperty("schema_version") int schemaVersion,
        Architecture architecture
) {
    public record Architecture(List<String> layers, Map<String, LayerRule> rules) {
        public Architecture {
            layers = layers == null ? List.of() : List.copyOf(layers);
            rules = rules == null ? Map.of() : Map.copyOf(rules);
        }
    }

    public record LayerRule(@JsonProperty("allow_dependencies") List<String> allowDependencies) {
        public LayerRule {
            allowDependencies = allowDependencies == null ? List.of() : List.copyOf(allowDependencies);
        }
    }
}
