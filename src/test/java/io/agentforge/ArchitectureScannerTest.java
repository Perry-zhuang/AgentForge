package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.JavaRole;
import io.agentforge.scanner.ArchitectureScanner;
import io.agentforge.scanner.ScanSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureScannerTest {
    private final ArchitectureScanner scanner = new ArchitectureScanner();

    @Test
    void acceptsControllerServiceRepositoryAndServiceMapperDependencies(@TempDir Path root) throws Exception {
        write(root, "src/controller/UserController.java", """
                package demo.controller;
                import demo.service.UserService;
                @RestController class UserController {
                  private final UserService service;
                  UserController(UserService service) { this.service = service; }
                }
                """);
        write(root, "src/service/UserService.java", """
                package demo.service;
                import demo.data.UserRepository;
                import demo.data.UserMapper;
                @Service class UserService {
                  private final UserRepository repository;
                  private final UserMapper mapper;
                  UserService(UserRepository repository, UserMapper mapper) { this.repository = repository; this.mapper = mapper; }
                }
                """);
        write(root, "src/data/UserRepository.java", "package demo.data; @Repository class UserRepository {}");
        write(root, "src/data/UserMapper.java", "package demo.data; @Mapper interface UserMapper {}");

        ArchitectureReport report = scan(root, Optional.of(defaultContract()));

        assertThat(report.relationCounts()).containsEntry("controller -> service", 1)
                .containsEntry("service -> repository", 1).containsEntry("service -> mapper", 1);
        assertThat(report.contractViolations()).isEmpty();
    }

    @Test
    void reportsControllerMapperAndRepositoryAsBypassesAndContractViolations(@TempDir Path root) throws Exception {
        write(root, "src/UserController.java", """
                package demo;
                @RestController class UserController {
                  private final UserMapper mapper;
                  private final UserRepository repository;
                  UserController(UserMapper mapper, UserRepository repository) { this.mapper=mapper; this.repository=repository; }
                }
                """);
        write(root, "src/UserMapper.java", "package demo; @Mapper interface UserMapper {}");
        write(root, "src/UserRepository.java", "package demo; @Repository class UserRepository {}");

        ArchitectureReport report = scan(root, Optional.of(defaultContract()));

        assertThat(report.possibleLayerBypasses()).hasSize(2);
        assertThat(report.contractViolations()).hasSize(2);
    }

    @Test
    void keepsHeuristicBypassSeparateWhenContractIsNotConfigured(@TempDir Path root) throws Exception {
        write(root, "src/UserController.java", "package demo; @RestController class UserController { private UserMapper mapper; }");
        write(root, "src/UserMapper.java", "package demo; @Mapper interface UserMapper {}");

        ArchitectureReport report = scan(root, Optional.empty());

        assertThat(report.contractConfigured()).isFalse();
        assertThat(report.analysisMode()).isEqualTo(ArchitectureAnalysisMode.LIGHTWEIGHT);
        assertThat(report.possibleLayerBypasses()).hasSize(1);
        assertThat(report.contractViolations()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("roleAnnotations")
    void appliesStableBeanRolePriority(String annotations, JavaRole expected, @TempDir Path root) throws Exception {
        write(root, "src/Bean.java", "package demo; " + annotations + " class Bean {}");

        ArchitectureReport report = scan(root, Optional.empty());

        assertThat(report.graph().nodes().get("demo.Bean").role()).isEqualTo(expected);
    }

    private static java.util.stream.Stream<Arguments> roleAnnotations() {
        return java.util.stream.Stream.of(
                Arguments.of("@Service", JavaRole.SERVICE),
                Arguments.of("@Component", JavaRole.COMPONENT),
                Arguments.of("@Service @Component", JavaRole.SERVICE),
                Arguments.of("@Repository @Component", JavaRole.REPOSITORY),
                Arguments.of("@Mapper @Component", JavaRole.MAPPER),
                Arguments.of("@RestController @Component", JavaRole.CONTROLLER));
    }

    private ArchitectureReport scan(Path root, Optional<ArchitectureContract> contract) {
        return scanner.scan(root, ScanSupport.walk(root).files(), contract);
    }

    private ArchitectureContract defaultContract() {
        return new ArchitectureContract(1, new ArchitectureContract.Architecture(
                List.of("controller", "service", "repository", "mapper"), Map.of(
                "controller", new ArchitectureContract.LayerRule(List.of("service")),
                "service", new ArchitectureContract.LayerRule(List.of("repository", "mapper")),
                "repository", new ArchitectureContract.LayerRule(List.of()),
                "mapper", new ArchitectureContract.LayerRule(List.of()))));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
