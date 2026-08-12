package io.agentforge.inspection;

import io.agentforge.model.ContextReport;
import io.agentforge.model.DependencyEdge;
import io.agentforge.model.DependencyGraph;
import io.agentforge.model.FileType;
import io.agentforge.model.JavaRole;
import io.agentforge.model.MatchEvidence;
import io.agentforge.model.RelevanceLevel;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.RelevantModule;
import io.agentforge.model.RelevantSourceArea;
import io.agentforge.model.RepositoryStructure;
import io.agentforge.model.SourceFileIndex;
import io.agentforge.model.TaskAnalysis;
import io.agentforge.scanner.RepositoryScanner;
import io.agentforge.scanner.ScanSupport;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RelevantFileEngine {
    private static final int MINIMUM_SCORE = 10;
    private static final Pattern CALLABLE_IDENTIFIER = Pattern.compile(
            "(?<![@.\\p{L}\\p{N}_$])([A-Za-z_$][A-Za-z0-9_$]{2,})\\s*\\(");
    private static final Set<String> NON_DOMAIN_CALLABLES = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "throw", "new",
            "super", "this", "assert", "record");
    private static final Set<FileType> SOURCE_TYPES = Set.of(
            FileType.JAVA, FileType.KOTLIN, FileType.JAVASCRIPT, FileType.JSX,
            FileType.TYPESCRIPT, FileType.TSX, FileType.VUE);

    public Result evaluate(Path root, List<Path> repositoryFiles, RepositoryStructure structure,
                           ContextReport context, TaskAnalysis task, int limit,
                           List<SourceFileIndex> javaIndex, DependencyGraph graph) {
        return new CoreRelevantFileEvaluator().evaluate(root, repositoryFiles, structure, context,
                task, limit, javaIndex, graph);
    }

    private Result evaluateLegacy(Path root, List<Path> repositoryFiles, RepositoryStructure structure,
                           ContextReport context, List<String> taskTerms, int limit,
                           List<SourceFileIndex> javaIndex, DependencyGraph graph) {
        List<String> warnings = new ArrayList<>();
        Map<String, SourceFileIndex> sources = new LinkedHashMap<>();
        javaIndex.stream().sorted(Comparator.comparing(SourceFileIndex::path))
                .forEach(source -> sources.put(source.path(), source));
        for (Path file : repositoryFiles.stream().sorted().toList()) {
            String relative = ScanSupport.relative(root, file);
            FileType type = RepositoryScanner.classify(file.getFileName().toString());
            if (!SOURCE_TYPES.contains(type) || type == FileType.JAVA || sources.containsKey(relative)) continue;
            String content = ScanSupport.readText(root, file, warnings);
            if (content.isBlank()) continue;
            String fileName = file.getFileName().toString();
            String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            String area = parent(relative);
            sources.put(relative, new SourceFileIndex(relative, type, "", stem, area, JavaRole.OTHER,
                    Set.of(), Set.of(), content));
        }

        List<ContextDocument> contextDocuments = contextDocuments(root, repositoryFiles, taskTerms, warnings);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (SourceFileIndex source : sources.values()) {
            Candidate candidate = new Candidate(source, moduleFor(source.path(), structure.modules()));
            lexicalEvidence(candidate, taskTerms);
            contextEvidence(candidate, contextDocuments);
            candidates.put(source.path(), candidate);
        }
        applyPhraseDerivedIdentifierEvidence(candidates.values(), taskTerms);
        applySourceAreaCohortEvidence(candidates.values());

        Map<String, String> qualifiedToPath = javaIndex.stream()
                .filter(source -> !source.qualifiedName().isBlank())
                .collect(Collectors.toMap(SourceFileIndex::qualifiedName, SourceFileIndex::path, (left, right) -> left));
        Set<String> seedPaths = candidates.values().stream()
                .filter(candidate -> candidate.score() >= 15)
                .map(candidate -> candidate.source.path())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        applyDependencyEvidence(candidates, graph, qualifiedToPath, seedPaths);
        applyNeighborEvidence(candidates, graph, qualifiedToPath, seedPaths);

        List<RelevantFile> relevantFiles = candidates.values().stream()
                .filter(candidate -> candidate.score() >= MINIMUM_SCORE)
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparingInt(Candidate::passivePriority)
                        .thenComparing(Comparator.comparingInt(Candidate::semanticSpecificity).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::directScore).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::structuralEvidenceCount).reversed())
                        .thenComparingInt(Candidate::rolePriority)
                        .thenComparing(candidate -> candidate.source.path()))
                .limit(limit)
                .map(Candidate::toRelevantFile)
                .toList();
        List<RelevantModule> modules = aggregateModules(relevantFiles);
        List<RelevantSourceArea> sourceAreas = aggregateSourceAreas(relevantFiles, sources);
        List<DependencyEdge> flow = relatedFlow(relevantFiles, graph, qualifiedToPath);
        return new Result(relevantFiles, modules, sourceAreas, flow, warnings);
    }

    private void lexicalEvidence(Candidate candidate, List<String> taskTerms) {
        SourceFileIndex source = candidate.source;
        if (source.javaSource() && matches(source.className(), taskTerms)) {
            candidate.add("CLASS_NAME", 30, "Class name matched task keyword: " + matchedTerms(source.className(), taskTerms));
        }
        String fileName = fileName(source.path());
        String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        if (matches(stem, taskTerms)) {
            candidate.add("FILE_NAME", 25, "File name matched task keyword: " + matchedTerms(stem, taskTerms));
        }
        String location = source.javaSource() ? source.packageName() : parent(source.path());
        if (!location.isBlank() && matches(location, taskTerms)) {
            candidate.add("SOURCE_AREA", 15, "Package or source directory matched: " + matchedTerms(location, taskTerms));
        }
        List<String> imports = source.imports().stream().filter(value -> matches(value, taskTerms)).sorted().toList();
        if (!imports.isEmpty()) {
            candidate.add("IMPORT", 10, "Imported type matched: " + imports.get(0));
        }
        List<String> contentMatches = localContentMatches(source.content(), taskTerms);
        if (!contentMatches.isEmpty()) {
            int points = sourceMatchPoints(contentMatches);
            if (contentMatches.size() >= 2 && closeMatches(source.content(), contentMatches)) {
                points = Math.min(25, points + 5);
            }
            candidate.add("SOURCE_KEYWORD", points, "Source keywords matched: " + String.join(", ", contentMatches));
            if (source.javaSource() && source.role() != JavaRole.OTHER && isSpecificContentMatch(contentMatches)) {
                candidate.add("JAVA_ROLE", rolePoints(source.role()), "Annotated architecture role: " + source.role());
            }
        }
    }

    private void applyPhraseDerivedIdentifierEvidence(java.util.Collection<Candidate> candidates,
                                                       List<String> taskTerms) {
        List<String> compoundTerms = taskTerms.stream()
                .filter(RelevantFileEngine::isCompoundCjkTerm)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (compoundTerms.isEmpty()) return;

        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            for (String term : compoundTerms) {
                findCallableAfter(candidate.source.content(), term).ifPresent(discovered::add);
            }
        }
        List<String> stableIdentifiers = discovered.stream()
                .filter(identifier -> identifier.length() >= 5)
                .filter(identifier -> {
                    long documentFrequency = candidates.stream()
                            .filter(candidate -> containsIdentifier(candidate.source.content(), identifier)).count();
                    return documentFrequency >= 2 && documentFrequency <= 50;
                })
                .sorted()
                .toList();

        for (Candidate candidate : candidates) {
            if (candidate.sourceKeywordPoints() >= 20) continue;
            List<String> matches = stableIdentifiers.stream()
                    .filter(identifier -> containsIdentifier(candidate.source.content(), identifier))
                    .limit(3)
                    .toList();
            if (matches.isEmpty()) continue;
            candidate.add("RELATED_IDENTIFIER", 20,
                    "Code identifier derived from an exact task phrase: " + String.join(", ", matches));
            if (candidate.source.javaSource() && candidate.source.role() != JavaRole.OTHER && !candidate.has("JAVA_ROLE")) {
                candidate.add("JAVA_ROLE", rolePoints(candidate.source.role()),
                        "Annotated architecture role: " + candidate.source.role());
            }
        }
    }

    private static Optional<String> findCallableAfter(String content, String term) {
        if (content == null || content.isBlank()) return Optional.empty();
        int match = content.indexOf(term);
        while (match >= 0) {
            int end = Math.min(content.length(), match + term.length() + 500);
            Matcher matcher = CALLABLE_IDENTIFIER.matcher(content.substring(match + term.length(), end));
            while (matcher.find()) {
                String identifier = matcher.group(1);
                if (!NON_DOMAIN_CALLABLES.contains(identifier.toLowerCase(Locale.ROOT))) {
                    return Optional.of(identifier);
                }
            }
            match = content.indexOf(term, match + term.length());
        }
        return Optional.empty();
    }

    private static boolean containsIdentifier(String content, String identifier) {
        if (content == null || content.isBlank()) return false;
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(identifier) + "(?![A-Za-z0-9_$])")
                .matcher(content).find();
    }

    private static boolean isCompoundCjkTerm(String term) {
        return term.length() >= 4 && term.codePoints().allMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private void contextEvidence(Candidate candidate, List<ContextDocument> documents) {
        SourceFileIndex source = candidate.source;
        List<String> identities = new ArrayList<>();
        identities.add(source.className());
        identities.add(fileStem(source.path()));
        if (!source.packageName().isBlank()) {
            String[] parts = candidate.businessArea().split("\\.");
            identities.add(parts[parts.length - 1]);
        }
        if (!candidate.module.equals(".")) identities.add(candidate.module);
        for (ContextDocument document : documents) {
            if (identities.stream().filter(value -> value != null && normalizeCompact(value).length() >= 3)
                    .anyMatch(value -> document.normalizedContent.contains(normalizeCompact(value)))) {
                candidate.add("CONTEXT", 5, document.path + " corroborated the task and candidate file");
                return;
            }
        }
    }

    private void applySourceAreaCohortEvidence(java.util.Collection<Candidate> candidates) {
        Map<String, List<Candidate>> areas = candidates.stream()
                .filter(candidate -> candidate.directScore() >= MINIMUM_SCORE
                        && candidate.cohortEligible() && candidate.cohortMatch())
                .collect(Collectors.groupingBy(Candidate::businessArea, LinkedHashMap::new, Collectors.toList()));
        int largestArea = areas.values().stream().mapToInt(List::size).max().orElse(0);
        for (Map.Entry<String, List<Candidate>> entry : areas.entrySet()) {
            List<Candidate> members = entry.getValue();
            boolean highConfidenceAnchor = members.stream().anyMatch(candidate -> candidate.evidence.stream()
                    .anyMatch(evidence -> (evidence.signal().equals("SOURCE_KEYWORD") && evidence.points() >= 15)
                            || evidence.signal().equals("CLASS_NAME") || evidence.signal().equals("FILE_NAME")
                            || evidence.signal().equals("SOURCE_AREA")));
            if (!highConfidenceAnchor || members.size() < 2 || members.size() < largestArea) continue;
            members.forEach(candidate -> candidate.add("SOURCE_AREA_COHORT", 10,
                    "Task evidence is concentrated in source area: " + entry.getKey()));
        }
    }

    private void applyDependencyEvidence(Map<String, Candidate> candidates, DependencyGraph graph,
                                         Map<String, String> qualifiedToPath, Set<String> seedPaths) {
        for (DependencyEdge edge : graph.edges()) {
            String fromPath = qualifiedToPath.get(edge.from());
            String toPath = qualifiedToPath.get(edge.to());
            if (fromPath == null || toPath == null || !seedPaths.contains(toPath)) continue;
            Candidate candidate = candidates.get(fromPath);
            Candidate seed = candidates.get(toPath);
            int points = seed != null && seed.strongStructuralMatch() ? 15 : 5;
            if (candidate != null) candidate.addOnce("DEPENDENCY", points,
                    "Field or constructor dependency matched relevant type: " + simpleName(edge.to()));
        }
    }

    private void applyNeighborEvidence(Map<String, Candidate> candidates, DependencyGraph graph,
                                       Map<String, String> qualifiedToPath, Set<String> seedPaths) {
        for (DependencyEdge edge : graph.edges()) {
            String fromPath = qualifiedToPath.get(edge.from());
            String toPath = qualifiedToPath.get(edge.to());
            if (fromPath == null || toPath == null) continue;
            if (seedPaths.contains(fromPath) && !fromPath.equals(toPath)) {
                Candidate candidate = candidates.get(toPath);
                Candidate seed = candidates.get(fromPath);
                int points = seed != null && seed.strongStructuralMatch() ? 10 : 5;
                if (candidate != null && !candidate.has("DEPENDENCY")) candidate.addOnce("ARCHITECTURE_NEIGHBOR", points,
                        "One-hop dependency neighbor of " + simpleName(edge.from()));
            }
            if (seedPaths.contains(toPath) && !fromPath.equals(toPath)) {
                Candidate candidate = candidates.get(fromPath);
                Candidate seed = candidates.get(toPath);
                int points = seed != null && seed.strongStructuralMatch() ? 10 : 5;
                if (candidate != null && !candidate.has("DEPENDENCY")) candidate.addOnce("ARCHITECTURE_NEIGHBOR", points,
                        "One-hop dependency neighbor of " + simpleName(edge.to()));
            }
        }
    }

    private List<String> localContentMatches(String content, List<String> taskTerms) {
        if (content == null || content.isBlank()) return List.of();
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> best = List.of();
        for (String anchor : taskTerms) {
            int from = 0;
            while (from < normalized.length()) {
                int match = normalized.indexOf(anchor.toLowerCase(Locale.ROOT), from);
                if (match < 0) break;
                int start = Math.max(0, match - 300);
                int end = Math.min(normalized.length(), match + anchor.length() + 300);
                String window = normalized.substring(start, end);
                List<String> matches = nonRedundantMatches(window, taskTerms);
                if (sourceMatchPoints(matches) > sourceMatchPoints(best)) best = matches;
                if (sourceMatchPoints(best) == 20) return best;
                from = match + Math.max(1, anchor.length());
            }
        }
        return best;
    }

    private List<String> nonRedundantMatches(String window, List<String> taskTerms) {
        List<String> matches = taskTerms.stream().filter(window::contains).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparingInt(taskTerms::indexOf)).toList();
        List<String> selected = new ArrayList<>();
        for (String match : matches) {
            if (selected.stream().noneMatch(existing -> existing.contains(match) || match.contains(existing))) {
                selected.add(match);
            }
            if (sourceMatchPoints(selected) == 20) break;
        }
        return selected;
    }

    private static int sourceMatchPoints(List<String> matches) {
        int points = matches.stream().mapToInt(RelevantFileEngine::sourceTermPoints).sum();
        boolean onlyShortCjk = matches.stream().allMatch(term -> term.length() <= 2 && term.codePoints()
                .allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN));
        return Math.min(onlyShortCjk ? 10 : 25, points);
    }

    private static boolean closeMatches(String content, List<String> matches) {
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (int left = 0; left < matches.size(); left++) {
            for (int right = left + 1; right < matches.size(); right++) {
                String first = matches.get(left).toLowerCase(Locale.ROOT);
                String second = matches.get(right).toLowerCase(Locale.ROOT);
                int firstIndex = normalized.indexOf(first);
                while (firstIndex >= 0) {
                    int start = Math.max(0, firstIndex - 40);
                    int end = Math.min(normalized.length(), firstIndex + first.length() + 40);
                    if (normalized.substring(start, end).contains(second)) return true;
                    firstIndex = normalized.indexOf(first, firstIndex + Math.max(1, first.length()));
                }
            }
        }
        return false;
    }

    private static int sourceTermPoints(String term) {
        boolean cjk = term.codePoints().allMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        if (!cjk) return 5;
        if (term.length() >= 4) return 25;
        if (term.length() == 3) return 10;
        return 5;
    }

    private static boolean isSpecificContentMatch(List<String> matches) {
        if (matches.size() >= 2) return true;
        return matches.get(0).length() >= 3;
    }

    private static int rolePoints(JavaRole role) {
        return switch (role) {
            case CONTROLLER, SERVICE -> 10;
            case REPOSITORY, MAPPER, COMPONENT -> 5;
            case OTHER -> 0;
        };
    }

    private List<ContextDocument> contextDocuments(Path root, List<Path> repositoryFiles, List<String> taskTerms,
                                                   List<String> warnings) {
        List<ContextDocument> documents = new ArrayList<>();
        for (Path file : repositoryFiles.stream().sorted().toList()) {
            String relative = ScanSupport.relative(root, file);
            if (!relative.equalsIgnoreCase("README.md") && !relative.equalsIgnoreCase("AGENTS.md")) continue;
            String content = ScanSupport.readText(root, file, warnings);
            if (taskTerms.stream().anyMatch(term -> containsTerm(content, term))) {
                documents.add(new ContextDocument(relative, normalizeCompact(content)));
            }
        }
        return documents;
    }

    private List<RelevantModule> aggregateModules(List<RelevantFile> files) {
        return aggregate(files, RelevantFile::module).entrySet().stream()
                .map(entry -> new RelevantModule(entry.getKey(), entry.getValue().score, entry.getValue().paths))
                .sorted(Comparator.comparingInt(RelevantModule::score).reversed()
                        .thenComparing(Comparator.comparingInt((RelevantModule module) -> module.relevantFiles().size()).reversed())
                        .thenComparing(RelevantModule::path))
                .toList();
    }

    private List<RelevantSourceArea> aggregateSourceAreas(List<RelevantFile> files, Map<String, SourceFileIndex> sources) {
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RelevantFile file : files) {
            SourceFileIndex source = sources.get(file.path());
            String area = source != null && source.javaSource() ? source.packageName() : parent(file.path());
            if (area.isBlank()) area = ".";
            aggregates.computeIfAbsent(area, ignored -> new Aggregate()).add(file);
        }
        return aggregates.entrySet().stream()
                .map(entry -> new RelevantSourceArea(entry.getKey(), entry.getValue().score, entry.getValue().paths))
                .sorted(Comparator.comparingInt(RelevantSourceArea::score).reversed()
                        .thenComparing(Comparator.comparingInt((RelevantSourceArea area) -> area.relevantFiles().size()).reversed())
                        .thenComparing(RelevantSourceArea::name))
                .toList();
    }

    private Map<String, Aggregate> aggregate(List<RelevantFile> files, Function<RelevantFile, String> classifier) {
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RelevantFile file : files) aggregates.computeIfAbsent(classifier.apply(file), ignored -> new Aggregate()).add(file);
        return aggregates;
    }

    private List<DependencyEdge> relatedFlow(List<RelevantFile> files, DependencyGraph graph,
                                             Map<String, String> qualifiedToPath) {
        Set<String> paths = files.stream().map(RelevantFile::path).collect(Collectors.toSet());
        return graph.edges().stream()
                .filter(edge -> paths.contains(qualifiedToPath.get(edge.from())) && paths.contains(qualifiedToPath.get(edge.to())))
                .sorted(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to))
                .toList();
    }

    private static String moduleFor(String path, List<String> modules) {
        return modules.stream().filter(module -> !module.equals(".") && (path.equals(module) || path.startsWith(module + "/")))
                .max(Comparator.comparingInt(String::length)).orElse(".");
    }

    private static boolean matches(String value, List<String> terms) {
        if (value == null || value.isBlank()) return false;
        String normalized = normalizeCompact(value);
        return terms.stream().map(RelevantFileEngine::normalizeCompact)
                .anyMatch(term -> term.length() >= 2 && normalized.contains(term));
    }

    private static String matchedTerms(String value, List<String> terms) {
        String normalized = normalizeCompact(value);
        return terms.stream().filter(term -> normalized.contains(normalizeCompact(term)))
                .limit(4).collect(Collectors.joining(", "));
    }

    private static boolean containsTerm(String value, String term) {
        if (value == null || term == null) return false;
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .contains(term.toLowerCase(Locale.ROOT));
    }

    private static String normalizeCompact(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String fileStem(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : ".";
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }

    public record Result(
            List<RelevantFile> relevantFiles,
            List<RelevantModule> relevantModules,
            List<RelevantSourceArea> relevantSourceAreas,
            List<DependencyEdge> relatedFlow,
            List<String> warnings
    ) {
        public Result {
            relevantFiles = List.copyOf(relevantFiles);
            relevantModules = List.copyOf(relevantModules);
            relevantSourceAreas = List.copyOf(relevantSourceAreas);
            relatedFlow = List.copyOf(relatedFlow);
            warnings = List.copyOf(warnings);
        }
    }

    private static final class Candidate {
        private final SourceFileIndex source;
        private final String module;
        private final List<MatchEvidence> evidence = new ArrayList<>();

        private Candidate(SourceFileIndex source, String module) {
            this.source = source;
            this.module = module;
        }

        private void add(String signal, int points, String detail) {
            evidence.add(new MatchEvidence(signal, points, detail));
        }

        private void addOnce(String signal, int points, String detail) {
            if (evidence.stream().noneMatch(item -> item.signal().equals(signal))) add(signal, points, detail);
        }

        private boolean has(String signal) {
            return evidence.stream().anyMatch(item -> item.signal().equals(signal));
        }

        private boolean strongStructuralMatch() {
            return evidence.stream().anyMatch(item -> item.signal().equals("CLASS_NAME")
                    || item.signal().equals("FILE_NAME") || item.signal().equals("SOURCE_AREA")
                    || item.signal().equals("IMPORT"));
        }

        private String businessArea() {
            String area = source.javaSource() ? source.packageName() : parent(source.path()).replace('/', '.');
            List<String> conventional = List.of("controller", "service", "impl", "repository", "mapper", "dto",
                    "vo", "entity", "model", "component", "config", "configuration", "util", "utils");
            List<String> parts = new ArrayList<>(List.of(area.split("\\.")));
            while (!parts.isEmpty() && conventional.contains(parts.get(parts.size() - 1).toLowerCase(Locale.ROOT))) {
                parts.remove(parts.size() - 1);
            }
            return parts.isEmpty() ? area : String.join(".", parts);
        }

        private boolean cohortEligible() {
            String area = source.javaSource() ? source.packageName() : parent(source.path()).replace('/', '.');
            String[] parts = area.split("\\.");
            if (parts.length == 0) return true;
            String leaf = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            return !Set.of("dto", "vo", "entity", "model", "enum", "enums", "constant", "constants",
                    "util", "utils", "config", "configuration").contains(leaf);
        }

        private int passivePriority() {
            return cohortEligible() ? 0 : 1;
        }

        private int semanticSpecificity() {
            return evidence.stream().filter(item -> item.signal().equals("SOURCE_KEYWORD")
                            || item.signal().equals("RELATED_IDENTIFIER")
                            || item.signal().equals("CLASS_NAME") || item.signal().equals("FILE_NAME")
                            || item.signal().equals("SOURCE_AREA"))
                    .mapToInt(MatchEvidence::points).max().orElse(0);
        }

        private int sourceKeywordPoints() {
            return evidence.stream().filter(item -> item.signal().equals("SOURCE_KEYWORD"))
                    .mapToInt(MatchEvidence::points).max().orElse(0);
        }

        private boolean cohortMatch() {
            return evidence.stream().anyMatch(item -> item.signal().equals("SOURCE_KEYWORD") && item.points() >= 10);
        }

        private int score() {
            return Math.min(100, evidence.stream().mapToInt(MatchEvidence::points).sum());
        }

        private int directScore() {
            return evidence.stream().filter(item -> !item.signal().equals("DEPENDENCY")
                    && !item.signal().equals("ARCHITECTURE_NEIGHBOR"))
                    .mapToInt(MatchEvidence::points).sum();
        }

        private int structuralEvidenceCount() {
            return (int) evidence.stream().filter(item -> !item.signal().equals("SOURCE_KEYWORD")
                    && !item.signal().equals("CONTEXT")).count();
        }

        private int rolePriority() {
            return switch (source.role()) {
                case CONTROLLER -> 0;
                case SERVICE -> 1;
                case REPOSITORY -> 2;
                case MAPPER -> 3;
                case COMPONENT -> 4;
                case OTHER -> 5;
            };
        }

        private RelevantFile toRelevantFile() {
            int score = score();
            RelevanceLevel level = score >= 50 ? RelevanceLevel.HIGH : score >= 25 ? RelevanceLevel.MEDIUM : RelevanceLevel.LOW;
            Optional<String> className = source.javaSource() ? Optional.of(source.className()) : Optional.empty();
            return new RelevantFile(source.path(), className, module, score, level, evidence);
        }
    }

    private static final class Aggregate {
        private int score;
        private final List<String> paths = new ArrayList<>();

        private void add(RelevantFile file) {
            score = Math.max(score, file.score());
            paths.add(file.path());
        }
    }

    private record ContextDocument(String path, String normalizedContent) {}
}
