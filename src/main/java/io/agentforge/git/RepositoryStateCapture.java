package io.agentforge.git;

import io.agentforge.model.RepositoryInfo;
import io.agentforge.model.RepositorySnapshot;
import io.agentforge.model.RepositorySnapshot.FileKind;
import io.agentforge.model.RepositorySnapshot.RepositoryFileState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class RepositoryStateCapture {
    private static final String CLEAN_STATUS = "  ";
    private static final int HASH_BUFFER_SIZE = 64 * 1024;

    private final GitClient git;
    private final GitRepositoryInspector repositoryInspector;

    public RepositoryStateCapture(GitClient git, GitRepositoryInspector repositoryInspector) {
        this.git = git;
        this.repositoryInspector = repositoryInspector;
    }

    public RepositorySnapshot capture(Path requestedPath) {
        RepositoryInfo repository = repositoryInspector.inspect(requestedPath);
        if (!repository.gitRepository()) {
            throw new IllegalArgumentException("Not a Git repository: "
                    + requestedPath.toAbsolutePath().normalize());
        }
        Path root = realPath(repository.gitRoot());
        List<GitStatusParser.Entry> statusEntries = status(root);
        Set<String> trackedPaths = paths(run(root, "ls-files", "-z", "--cached"));
        Set<String> untrackedPaths = paths(run(root, "ls-files", "-z", "--others", "--exclude-standard"));
        Map<String, String> indexIdentities = indexIdentities(run(root, "ls-files", "-z", "--stage"));
        Map<String, GitStatusParser.Entry> statusByPath = statusByPath(statusEntries);

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(trackedPaths);
        allPaths.addAll(untrackedPaths);
        statusEntries.forEach(entry -> allPaths.add(entry.path()));

        List<RepositoryFileState> files = new ArrayList<>();
        for (String path : allPaths) {
            GitStatusParser.Entry status = statusByPath.get(path);
            boolean tracked = trackedPaths.contains(path) || status != null && !status.untracked();
            String indexIdentity = indexIdentities.get(path);
            Path file = resolve(root, path);
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            FileKind kind = kind(file, exists, indexIdentity);
            String worktreeIdentity = switch (kind) {
                case REGULAR -> hashFile(file);
                case SYMLINK -> hashBytes(readLink(file).getBytes(StandardCharsets.UTF_8));
                case GITLINK, MISSING -> null;
            };
            files.add(new RepositoryFileState(path, status == null ? null : status.oldPath(), tracked, exists,
                    kind, worktreeIdentity, indexIdentity,
                    status == null ? CLEAN_STATUS : status.porcelainStatus()));
        }
        return new RepositorySnapshot(RepositorySnapshot.CURRENT_SCHEMA_VERSION, root.toString(),
                repository.branch(), repository.head(), files);
    }

    private List<GitStatusParser.Entry> status(Path root) {
        GitClient.Result result = git.statusPorcelain(root, ".");
        if (!result.succeeded()) throw gitFailure("Git status", result);
        return GitStatusParser.parse(result.output(), ".");
    }

    private String run(Path root, String... arguments) {
        GitClient.Result result = git.run(root, arguments);
        if (!result.succeeded()) throw gitFailure("Git " + arguments[0], result);
        return result.output();
    }

    private IllegalStateException gitFailure(String operation, GitClient.Result result) {
        return new IllegalStateException(operation + " failed: " + result.error());
    }

    private Set<String> paths(String nulDelimited) {
        Set<String> result = new TreeSet<>();
        for (String token : nulDelimited.split("\\u0000", -1)) {
            if (!token.isEmpty()) result.add(token.replace('\\', '/'));
        }
        return result;
    }

    private Map<String, GitStatusParser.Entry> statusByPath(List<GitStatusParser.Entry> entries) {
        Map<String, GitStatusParser.Entry> result = new LinkedHashMap<>();
        for (GitStatusParser.Entry entry : entries) {
            if (result.put(entry.path(), entry) != null) {
                throw new IllegalStateException("Git status returned duplicate path: " + entry.path());
            }
        }
        return result;
    }

    private Map<String, String> indexIdentities(String output) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String token : output.split("\\u0000", -1)) {
            if (token.isEmpty()) continue;
            int tab = token.indexOf('\t');
            if (tab < 0) throw new IllegalStateException("Invalid Git index entry");
            String[] fields = token.substring(0, tab).trim().split("\\s+");
            if (fields.length != 3) throw new IllegalStateException("Invalid Git index identity");
            String path = token.substring(tab + 1).replace('\\', '/');
            String identity = fields[0] + ":" + fields[1] + ":" + fields[2];
            grouped.computeIfAbsent(path, ignored -> new ArrayList<>()).add(identity);
        }
        Map<String, String> result = new LinkedHashMap<>();
        grouped.forEach((path, identities) -> {
            identities.sort(String::compareTo);
            result.put(path, String.join("|", identities));
        });
        return result;
    }

    private FileKind kind(Path file, boolean exists, String indexIdentity) {
        if (indexIdentity != null && indexIdentity.startsWith("160000:")) return FileKind.GITLINK;
        if (!exists) return FileKind.MISSING;
        if (Files.isSymbolicLink(file)) return FileKind.SYMLINK;
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return FileKind.REGULAR;
        throw new IllegalStateException("Unsupported repository path type: " + file);
    }

    private Path resolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Git path escapes repository root: " + relative);
        }
        return resolved;
    }

    private Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot resolve repository root: " + path, exception);
        }
    }

    private String readLink(Path file) {
        try {
            return Files.readSymbolicLink(file).toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read symbolic link: " + file, exception);
        }
    }

    private String hashFile(Path file) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            while (input.read(buffer) >= 0) {
                // DigestInputStream updates the digest while streaming.
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot hash repository file: " + file, exception);
        }
    }

    private String hashBytes(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
