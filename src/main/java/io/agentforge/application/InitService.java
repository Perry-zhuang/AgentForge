package io.agentforge.application;

import io.agentforge.config.DefaultConfigs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InitService {
    public InitResult initialize(Path requestedPath) {
        Path root = requestedPath.toAbsolutePath().normalize();
        if (!Files.exists(root)) throw new IllegalArgumentException("Path does not exist: " + root);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Path is not a directory: " + root);
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Path configDirectory = root.resolve(".agentforge");
        try {
            Files.createDirectories(configDirectory);
            create(root, configDirectory.resolve("project.yaml"), DefaultConfigs.PROJECT_YAML.formatted(projectName(root)), created, skipped);
            create(root, configDirectory.resolve("architecture.yaml"), DefaultConfigs.ARCHITECTURE_YAML, created, skipped);
            create(root, configDirectory.resolve("policies.yaml"), DefaultConfigs.POLICIES_YAML, created, skipped);
            create(root, configDirectory.resolve("README.md"), DefaultConfigs.AGENTFORGE_README, created, skipped);
            create(root, root.resolve("AGENTS.md"), DefaultConfigs.AGENTS_MD, created, skipped);
        } catch (IOException exception) {
            throw new IllegalStateException("Initialization failed: " + exception.getMessage(), exception);
        }
        return new InitResult(root, created, skipped);
    }

    private void create(Path root, Path file, String content, List<String> created, List<String> skipped) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (Files.exists(file)) {
            skipped.add(relative);
            return;
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            created.add(relative);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            skipped.add(relative);
        }
    }

    private String projectName(Path root) {
        Path name = root.getFileName();
        return name == null ? "project" : name.toString().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    public record InitResult(Path path, List<String> created, List<String> skipped) {
        public InitResult {
            created = List.copyOf(created);
            skipped = List.copyOf(skipped);
        }
    }
}
