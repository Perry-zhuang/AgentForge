package io.agentforge.discovery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryResultParser {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public DiscoveryResult parse(String json, Path repositoryRoot) {
        if (json == null || json.isBlank() || repositoryRoot == null) {
            throw new DiscoveryException("Discovery result is empty");
        }
        try {
            DiscoveryResult result = mapper.readValue(json, DiscoveryResult.class);
            validatePaths(result, repositoryRoot.toRealPath());
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            throw new DiscoveryException("Invalid discovery result: " + exception.getMessage(), exception);
        }
    }

    private void validatePaths(DiscoveryResult result, Path root) {
        List<String> all = new java.util.ArrayList<>();
        all.addAll(result.proposedPrimaryScope());
        all.addAll(result.proposedSecondaryScope());
        all.addAll(result.referenceFiles());
        result.findings().forEach(finding -> all.addAll(finding.evidence()));
        result.relevantFlows().forEach(flow -> all.addAll(flow.files()));
        Set<String> categories = new HashSet<>();
        for (String path : result.proposedPrimaryScope()) addCategory(categories, path);
        for (String path : result.proposedSecondaryScope()) addCategory(categories, path);
        for (String path : result.referenceFiles()) addCategory(categories, path);
        for (String path : all) {
            String normalized = normalize(path);
            Path resolved = root.resolve(normalized).normalize();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("Unknown proposed discovery file: " + path);
            }
        }
    }

    private void addCategory(Set<String> categories, String path) {
        String normalized = normalize(path);
        if (!categories.add(normalized)) throw new IllegalArgumentException("File appears in multiple scope categories: " + path);
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Discovery path is required");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.contains("*") || normalized.contains("?")) {
            throw new IllegalArgumentException("Discovery path must be an exact repository-relative path: " + path);
        }
        return normalized;
    }
}
