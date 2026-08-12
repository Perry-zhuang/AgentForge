package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.git.GitClient;
import io.agentforge.git.GitRepositoryInspector;
import io.agentforge.model.BackendFramework;
import io.agentforge.model.BuildSystem;
import io.agentforge.model.FileType;
import io.agentforge.model.FrontendFramework;
import io.agentforge.scanner.RepositoryScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryScannerTest {
    private final RepositoryScanner scanner = new RepositoryScanner(new GitRepositoryInspector(new GitClient()));

    @Test
    void recognizesMavenSpringVueFilesTestsAndExclusions(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<artifactId>spring-boot-starter</artifactId>");
        write(root, "frontend/package.json", "{\"dependencies\":{\"vue\":\"3\"}}");
        write(root, "src/main/java/demo/App.java", "package demo; import org.springframework.stereotype.Service; class App {}");
        write(root, "src/test/java/demo/AppTest.java", "class AppTest {}");
        write(root, "target/Ignored.java", "class Ignored {}");
        write(root, "schema/V1.sql", "select 1;");

        RepositoryScanner.Result result = scanner.scan(root);

        assertThat(result.technology().buildSystems()).contains(BuildSystem.MAVEN, BuildSystem.NODE);
        assertThat(result.technology().backendFrameworks()).contains(BackendFramework.SPRING_BOOT);
        assertThat(result.technology().frontendFrameworks()).contains(FrontendFramework.VUE);
        assertThat(result.structure().modules()).containsExactly(".", "frontend");
        assertThat(result.structure().count(FileType.JAVA)).isEqualTo(2);
        assertThat(result.structure().count(FileType.SQL)).isEqualTo(1);
        assertThat(result.structure().testFiles()).isEqualTo(1);
        assertThat(result.repository().gitRepository()).isFalse();
    }

    @Test
    void handlesUnbornGitRepository(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        write(root, "src/App.java", "class App {}");

        assertThat(scanner.scan(root).repository())
                .extracting(repository -> repository.head(), repository -> repository.branch())
                .containsExactly("UNBORN", "main");
    }

    @Test
    void identifiesDetachedHead(@TempDir Path root) throws Exception {
        git(root, "init", "-b", "main");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "AgentForge Test");
        write(root, "README.md", "demo");
        git(root, "add", ".");
        git(root, "commit", "-m", "base");
        git(root, "checkout", "--detach");

        assertThat(scanner.scan(root).repository())
                .extracting(repository -> repository.branch(), repository -> repository.head().length())
                .containsExactly("DETACHED", 40);
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
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        assertThat(process.waitFor()).isZero();
    }
}
