package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineCommandTest {
    @Test
    void taskAwareCapturePersistsDeterministicPreExecutionBoundaryAndStillCompares(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path first = temp.resolve("first.json");
        Path second = temp.resolve("second.json");
        Path third = temp.resolve("third.json");
        String task = "修复 Foo 异常";

        CliTestHarness.Result firstCapture = CliTestHarness.execute("baseline", "capture", root.toString(),
                "--task", task, "--output", first.toString());
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--task", task, "--output", second.toString()).exitCode()).isZero();
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--task", task, "--output", third.toString()).exitCode()).isZero();
        CliTestHarness.Result compare = CliTestHarness.execute("baseline", "compare", root.toString(),
                "--baseline", first.toString());

        assertThat(firstCapture.exitCode()).isZero();
        assertThat(firstCapture.output()).contains("Schema Version           2", "Engineering Boundary",
                "Task Type                BUGFIX", "TASK-AWARE BASELINE CAPTURED");
        assertThat(Files.readString(first)).contains(
                "\"schemaVersion\" : 2",
                "\"repositorySnapshot\"",
                "\"engineeringBoundary\"",
                "\"task\" : \"修复 Foo 异常\"",
                "\"taskType\" : \"BUGFIX\"",
                "\"primaryPaths\"",
                "Foo.java",
                "\"changePolicy\"",
                "\"architectureContract\" : null");
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second))
                .isEqualTo(Files.readAllBytes(third));
        assertThat(compare.exitCode()).isZero();
        assertThat(compare.output()).contains("Files                    0", "EXECUTION DELTA NONE");
    }

    @Test
    void capturesAndComparesDirtyRepositoryWithoutAttributingExistingChanges(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        write(root, "Foo.java", "existing dirty\n");
        Path baseline = temp.resolve("baseline.json");

        CliTestHarness.Result capture = CliTestHarness.execute(
                "baseline", "capture", root.toString(), "--output", baseline.toString());
        CliTestHarness.Result compare = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", baseline.toString());

        assertThat(capture.exitCode()).isZero();
        assertThat(capture.output()).contains("BASELINE CAPTURED", "Ignored Files            EXCLUDED");
        assertThat(compare.exitCode()).isZero();
        assertThat(compare.output()).contains(
                "Branch Changed           false",
                "HEAD Changed             false",
                "Files                    0",
                "EXECUTION DELTA NONE");
    }

    @Test
    void dirtyFileModifiedAgainAndNewFileProduceDeterministicDeltaOutput(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        write(root, "Foo.java", "dirty before\n");
        Path baseline = temp.resolve("baseline.json");
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString()).exitCode()).isZero();
        write(root, "Foo.java", "dirty after\n");
        write(root, "new.bin", "new\n");

        CliTestHarness.Result first = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", baseline.toString());
        CliTestHarness.Result second = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", baseline.toString());
        CliTestHarness.Result third = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", baseline.toString());

        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).contains(
                "MODIFIED Foo.java [CONTENT_CHANGED]",
                "ADDED new.bin [CONTENT_CHANGED, GIT_STATE_CHANGED]",
                "EXECUTION DELTA CHANGED");
        assertThat(first.output()).isEqualTo(second.output()).isEqualTo(third.output());
    }

    @Test
    void persistedJsonIsByteIdenticalForSameState(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path first = temp.resolve("first.json");
        Path second = temp.resolve("second.json");
        Path third = temp.resolve("third.json");

        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", first.toString()).exitCode()).isZero();
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", second.toString()).exitCode()).isZero();
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", third.toString()).exitCode()).isZero();

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second))
                .isEqualTo(Files.readAllBytes(third));
    }

    @Test
    void stagingOnlyChangeIsReportedWithoutContentChange(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        write(root, "Foo.java", "dirty\n");
        Path baseline = temp.resolve("baseline.json");
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString()).exitCode()).isZero();
        git(root, "add", "Foo.java");

        CliTestHarness.Result result = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", baseline.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("GIT_STATE_CHANGED Foo.java [GIT_STATE_CHANGED]");
    }

    @Test
    void distinguishesPreExistingRenameFromNewRenameAndLaterModification(@TempDir Path temp) throws Exception {
        Path introducedRoot = repository(temp.resolve("introduced"));
        Path introducedBaseline = temp.resolve("introduced.json");
        assertThat(CliTestHarness.execute("baseline", "capture", introducedRoot.toString(),
                "--output", introducedBaseline.toString()).exitCode()).isZero();
        git(introducedRoot, "mv", "Foo.java", "Renamed.java");

        CliTestHarness.Result introduced = CliTestHarness.execute("baseline", "compare", introducedRoot.toString(),
                "--baseline", introducedBaseline.toString());

        assertThat(introduced.output()).contains(
                "RENAMED Foo.java -> Renamed.java",
                "EXECUTION DELTA CHANGED");

        Path existingRoot = repository(temp.resolve("existing"));
        git(existingRoot, "mv", "Foo.java", "Renamed.java");
        Path existingBaseline = temp.resolve("existing.json");
        assertThat(CliTestHarness.execute("baseline", "capture", existingRoot.toString(),
                "--output", existingBaseline.toString()).exitCode()).isZero();

        CliTestHarness.Result unchanged = CliTestHarness.execute("baseline", "compare", existingRoot.toString(),
                "--baseline", existingBaseline.toString());
        assertThat(unchanged.output()).contains("Files                    0", "EXECUTION DELTA NONE");

        write(existingRoot, "Renamed.java", "modified after pre-existing rename\n");
        CliTestHarness.Result modified = CliTestHarness.execute("baseline", "compare", existingRoot.toString(),
                "--baseline", existingBaseline.toString());
        assertThat(modified.output()).contains("MODIFIED Renamed.java", "EXECUTION DELTA CHANGED")
                .doesNotContain("RENAMED Foo.java -> Renamed.java");
    }

    @Test
    void reportsDeleteAndBranchHeadChangesAsFacts(@TempDir Path temp) throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path baseline = temp.resolve("baseline.json");
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString()).exitCode()).isZero();
        Files.delete(root.resolve("Foo.java"));
        git(root, "switch", "-c", "feature");
        write(root, "README.md", "new head\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "advance head");

        CliTestHarness.Result result = CliTestHarness.execute("baseline", "compare", root.toString(),
                "--baseline", baseline.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains(
                "Branch Changed           true",
                "HEAD Changed             true",
                "DELETED Foo.java",
                "EXECUTION DELTA CHANGED");
    }

    @Test
    void rejectsRepositoryInternalOutputAndInvalidBaselineInputsWithExitTwo(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path inside = root.resolve("baseline.json");
        Path invalid = temp.resolve("invalid.json");
        Files.writeString(invalid, "{not-json}");

        CliTestHarness.Result internal = CliTestHarness.execute(
                "baseline", "capture", root.toString(), "--output", inside.toString());
        CliTestHarness.Result missing = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", temp.resolve("missing.json").toString());
        CliTestHarness.Result malformed = CliTestHarness.execute(
                "baseline", "compare", root.toString(), "--baseline", invalid.toString());
        CliTestHarness.Result invocation = CliTestHarness.execute("baseline", "compare", root.toString());

        assertThat(internal.exitCode()).isEqualTo(2);
        assertThat(internal.output()).contains("must be outside the target repository");
        assertThat(Files.exists(inside)).isFalse();
        assertThat(missing.exitCode()).isEqualTo(2);
        assertThat(missing.output()).contains("does not exist");
        assertThat(malformed.exitCode()).isEqualTo(2);
        assertThat(malformed.output()).contains("Invalid baseline file");
        assertThat(invocation.exitCode()).isEqualTo(2);
        assertThat(invocation.output()).contains("requires --baseline");
    }

    @Test
    void unsupportedSchemaAndRepositoryMismatchReturnExitTwo(@TempDir Path temp) throws Exception {
        Path firstRoot = repository(temp.resolve("first-repo"));
        Path secondRoot = repository(temp.resolve("second-repo"));
        Path baseline = temp.resolve("baseline.json");
        assertThat(CliTestHarness.execute("baseline", "capture", firstRoot.toString(),
                "--output", baseline.toString()).exitCode()).isZero();
        Path unsupported = temp.resolve("unsupported.json");
        Files.writeString(unsupported, Files.readString(baseline).replaceFirst(
                "\\\"schemaVersion\\\"\\s*:\\s*1", "\"schemaVersion\" : 99"));

        CliTestHarness.Result schema = CliTestHarness.execute("baseline", "compare", firstRoot.toString(),
                "--baseline", unsupported.toString());
        CliTestHarness.Result mismatch = CliTestHarness.execute("baseline", "compare", secondRoot.toString(),
                "--baseline", baseline.toString());

        assertThat(schema.exitCode()).isEqualTo(2);
        assertThat(schema.output()).contains("Unsupported baseline schema version: 99");
        assertThat(mismatch.exitCode()).isEqualTo(2);
        assertThat(mismatch.output()).contains("does not match current repository");
    }

    @Test
    void forceOverwritesExternalBaselineButCompareRejectsBaselineInsideRepository(@TempDir Path temp)
            throws Exception {
        Path root = repository(temp.resolve("repo"));
        Path baseline = temp.resolve("baseline.json");
        assertThat(CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString()).exitCode()).isZero();

        CliTestHarness.Result withoutForce = CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString());
        CliTestHarness.Result withForce = CliTestHarness.execute("baseline", "capture", root.toString(),
                "--output", baseline.toString(), "--force");
        Path inside = root.resolve("copied-baseline.json");
        Files.copy(baseline, inside);
        CliTestHarness.Result internalCompare = CliTestHarness.execute("baseline", "compare", root.toString(),
                "--baseline", inside.toString());

        assertThat(withoutForce.exitCode()).isEqualTo(2);
        assertThat(withoutForce.output()).contains("use --force");
        assertThat(withForce.exitCode()).isZero();
        assertThat(internalCompare.exitCode()).isEqualTo(2);
        assertThat(internalCompare.output()).contains("must be outside the target repository");
    }

    private static Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "Foo.java", "baseline\n");
        write(root, ".gitignore", "*.ignored\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        return root;
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(new String(output)).isZero();
    }
}
