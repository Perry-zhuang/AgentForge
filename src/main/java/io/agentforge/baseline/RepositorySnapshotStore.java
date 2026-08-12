package io.agentforge.baseline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentforge.model.RepositorySnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Component;

@Component
public class RepositorySnapshotStore {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public Path write(RepositorySnapshot snapshot, Path requested, boolean force) {
        Path destination = validateDestination(snapshot.repositoryRoot(), requested, force);
        String json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
                    .replace("\r\n", "\n") + "\n";
            if (force) {
                Files.writeString(destination, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                Files.writeString(destination, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            return destination;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write baseline file " + destination + ": "
                    + exception.getMessage(), exception);
        }
    }

    public RepositorySnapshot read(Path requested) {
        Path source = requested.toAbsolutePath().normalize();
        if (!Files.exists(source)) throw new IllegalArgumentException("Baseline file does not exist: " + source);
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Baseline path is not a file: " + source);
        try {
            RepositorySnapshot snapshot = mapper.readValue(source.toFile(), RepositorySnapshot.class);
            requireCurrentSchema(snapshot);
            return snapshot;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid baseline file " + source + ": "
                    + exception.getMessage(), exception);
        }
    }

    public void requireOutsideRepository(String repositoryRoot, Path requested) {
        Path repository = realPath(Path.of(repositoryRoot), "repository root");
        Path candidate = canonicalCandidate(requested);
        if (candidate.startsWith(repository)) {
            throw new IllegalArgumentException("Baseline file must be outside the target repository: " + candidate);
        }
    }

    private Path validateDestination(String repositoryRoot, Path requested, boolean force) {
        Path destination = requested.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null || !Files.exists(parent)) {
            throw new IllegalArgumentException("Output parent directory does not exist: " + parent);
        }
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Output parent is not a directory: " + parent);
        }
        if (Files.isDirectory(destination)) {
            throw new IllegalArgumentException("Output path is a directory: " + destination);
        }
        if (Files.exists(destination) && !force) {
            throw new IllegalArgumentException("Output file already exists; use --force to overwrite: " + destination);
        }
        requireOutsideRepository(repositoryRoot, destination);
        return destination;
    }

    private Path canonicalCandidate(Path requested) {
        Path candidate = requested.toAbsolutePath().normalize();
        if (Files.exists(candidate)) return realPath(candidate, "baseline path");
        Path parent = candidate.getParent();
        if (parent == null) throw new IllegalArgumentException("Baseline path has no parent: " + candidate);
        return realPath(parent, "baseline parent").resolve(candidate.getFileName()).normalize();
    }

    private Path realPath(Path path, String label) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot resolve " + label + ": " + path, exception);
        }
    }

    private void requireCurrentSchema(RepositorySnapshot snapshot) {
        if (snapshot.schemaVersion() != RepositorySnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported baseline schema version: " + snapshot.schemaVersion());
        }
    }
}
