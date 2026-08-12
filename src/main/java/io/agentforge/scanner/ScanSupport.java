package io.agentforge.scanner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ScanSupport {
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "build", ".gradle", "node_modules", "dist", ".idea", ".vscode", ".cache"
    );

    private ScanSupport() {}

    public static WalkResult walk(Path root) {
        List<Path> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && (Files.isSymbolicLink(dir) || EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    warnings.add("Cannot read " + relative(root, file) + ": " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            warnings.add("Repository walk failed: " + exception.getMessage());
        }
        return new WalkResult(files, warnings);
    }

    public static String readText(Path root, Path file, List<String> warnings) {
        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_BYTES) {
                warnings.add("Skipped large text file " + relative(root, file) + " (" + size + " bytes)");
                return "";
            }
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            warnings.add("Skipped non-UTF-8 file " + relative(root, file));
        } catch (IOException exception) {
            warnings.add("Cannot read " + relative(root, file) + ": " + exception.getMessage());
        }
        return "";
    }

    public static String relative(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return file.toString().replace('\\', '/');
        }
    }

    public record WalkResult(List<Path> files, List<String> warnings) {
        public WalkResult {
            files = List.copyOf(files);
            warnings = List.copyOf(warnings);
        }
    }
}
