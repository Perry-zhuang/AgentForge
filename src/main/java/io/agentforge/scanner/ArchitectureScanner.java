package io.agentforge.scanner;

import io.agentforge.model.ArchitectureContract;
import io.agentforge.model.ArchitectureAnalysisMode;
import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.JavaRole;
import io.agentforge.model.JavaTypeInfo;
import io.agentforge.model.FileType;
import io.agentforge.model.SourceFileIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ArchitectureScanner {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern FIELD = Pattern.compile("\\b(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?([A-Z][\\w.]*)\\s+\\w+\\s*(?:=[^;]*)?;");
    private static final Pattern PARAMETER_TYPE = Pattern.compile("(?:^|,)\\s*(?:final\\s+)?(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*([A-Z][\\w.]*)\\s+\\w+");

    public ArchitectureReport scan(Path root, List<Path> repositoryFiles, Optional<ArchitectureContract> contract) {
        return scanDetailed(root, repositoryFiles, contract).report();
    }

    public DetailedResult scanDetailed(Path root, List<Path> repositoryFiles, Optional<ArchitectureContract> contract) {
        List<String> warnings = new ArrayList<>();
        List<JavaSource> sources = new ArrayList<>();
        Map<String, JavaTypeInfo> nodes = new LinkedHashMap<>();
        Map<String, String> simpleToQualified = new HashMap<>();
        Map<JavaRole, Integer> roleCounts = new EnumMap<>(JavaRole.class);

        for (Path file : repositoryFiles.stream().sorted().toList()) {
            if (!file.getFileName().toString().endsWith(".java")) continue;
            String content = ScanSupport.readText(root, file, warnings);
            if (content.isBlank()) continue;
            String analysisContent = stripCommentsAndLiterals(content);
            String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
            String packageName = match(PACKAGE, analysisContent).orElse("");
            String qualifiedName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
            JavaRole role = detectRole(analysisContent);
            JavaTypeInfo type = new JavaTypeInfo(qualifiedName, simpleName, packageName, role, ScanSupport.relative(root, file));
            nodes.put(qualifiedName, type);
            simpleToQualified.put(simpleName, qualifiedName);
            if (role != JavaRole.OTHER) roleCounts.merge(role, 1, Integer::sum);
            Map<String, String> imports = imports(analysisContent);
            Set<String> dependencies = dependencyTypes(type.simpleName(), analysisContent);
            sources.add(new JavaSource(type, content, analysisContent, imports, dependencies));
        }

        Set<DependencyEdge> edgeSet = new LinkedHashSet<>();
        for (JavaSource source : sources) {
            Set<String> candidateTypes = source.dependencyTypes();
            for (String candidate : candidateTypes) {
                String simple = candidate.contains(".") ? candidate.substring(candidate.lastIndexOf('.') + 1) : candidate;
                String qualified = source.imports().get(simple);
                if (qualified == null) {
                    String samePackage = source.type().packageName().isBlank() ? simple : source.type().packageName() + "." + simple;
                    qualified = nodes.containsKey(samePackage) ? samePackage : simpleToQualified.get(simple);
                }
                JavaTypeInfo target = nodes.get(qualified);
                if (target != null && !target.qualifiedName().equals(source.type().qualifiedName())) {
                    edgeSet.add(new DependencyEdge(source.type().qualifiedName(), target.qualifiedName(), source.type().role(), target.role()));
                }
            }
        }

        List<DependencyEdge> edges = edgeSet.stream()
                .sorted(java.util.Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to))
                .toList();
        Map<String, Integer> relations = new LinkedHashMap<>();
        List<DependencyEdge> bypasses = new ArrayList<>();
        List<DependencyEdge> violations = new ArrayList<>();
        for (DependencyEdge edge : edges) {
            if (edge.fromRole() != JavaRole.OTHER && edge.toRole() != JavaRole.OTHER) {
                relations.merge(edge.relation(), 1, Integer::sum);
            }
            if (edge.fromRole() == JavaRole.CONTROLLER
                    && (edge.toRole() == JavaRole.MAPPER || edge.toRole() == JavaRole.REPOSITORY)) {
                bypasses.add(edge);
            }
            if (contract.isPresent() && violates(edge, contract.get())) violations.add(edge);
        }
        ArchitectureReport report = new ArchitectureReport(new DependencyGraph(nodes, edges), roleCounts, relations, bypasses,
                violations, contract.isPresent(), ArchitectureAnalysisMode.LIGHTWEIGHT, warnings);
        List<SourceFileIndex> sourceIndex = sources.stream().map(source -> new SourceFileIndex(
                source.type().path(), FileType.JAVA, source.type().qualifiedName(), source.type().simpleName(),
                source.type().packageName(), source.type().role(), new LinkedHashSet<>(source.imports().values()),
                source.dependencyTypes(), source.rawContent())).toList();
        return new DetailedResult(report, sourceIndex);
    }

    private boolean violates(DependencyEdge edge, ArchitectureContract contract) {
        String from = edge.fromRole().layer();
        String to = edge.toRole().layer();
        if (edge.fromRole() == JavaRole.OTHER || edge.toRole() == JavaRole.OTHER) return false;
        if (!contract.architecture().layers().contains(from) || !contract.architecture().layers().contains(to)) return false;
        ArchitectureContract.LayerRule rule = contract.architecture().rules().get(from);
        return rule != null && !rule.allowDependencies().contains(to);
    }

    private Set<String> dependencyTypes(String owner, String content) {
        Set<String> types = new LinkedHashSet<>();
        Matcher fields = FIELD.matcher(content);
        while (fields.find()) types.add(stripGenericArray(fields.group(1)));
        Pattern constructor = Pattern.compile("\\b" + Pattern.quote(owner) + "\\s*\\(([^)]*)\\)");
        Matcher constructors = constructor.matcher(content);
        while (constructors.find()) {
            Matcher parameters = PARAMETER_TYPE.matcher(constructors.group(1));
            while (parameters.find()) types.add(stripGenericArray(parameters.group(1)));
        }
        return types;
    }

    private String stripGenericArray(String type) {
        return type.replaceAll("<.*>", "").replace("[]", "");
    }

    private Map<String, String> imports(String content) {
        Map<String, String> imports = new HashMap<>();
        Matcher matcher = IMPORT.matcher(content);
        while (matcher.find()) {
            String qualified = matcher.group(1);
            if (!qualified.endsWith(".*")) imports.put(qualified.substring(qualified.lastIndexOf('.') + 1), qualified);
        }
        return imports;
    }

    private Optional<String> match(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private JavaRole detectRole(String content) {
        if (content.contains("@RestController") || content.contains("@Controller")) return JavaRole.CONTROLLER;
        if (content.contains("@Service")) return JavaRole.SERVICE;
        if (content.contains("@Repository")) return JavaRole.REPOSITORY;
        if (content.contains("@Mapper")) return JavaRole.MAPPER;
        if (content.contains("@Component")) return JavaRole.COMPONENT;
        return JavaRole.OTHER;
    }

    private String stripCommentsAndLiterals(String content) {
        return content
                .replaceAll("(?s)\\\"\\\"\\\".*?\\\"\\\"\\\"", " ")
                .replaceAll("(?s)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", " ")
                .replaceAll("(?s)'(?:\\\\.|[^'\\\\])*'", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    public record DetailedResult(ArchitectureReport report, List<SourceFileIndex> sourceIndex) {
        public DetailedResult {
            sourceIndex = List.copyOf(sourceIndex);
        }
    }

    private record JavaSource(JavaTypeInfo type, String rawContent, String content,
                              Map<String, String> imports, Set<String> dependencyTypes) {}
}
