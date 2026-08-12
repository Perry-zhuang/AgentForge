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
import java.util.stream.Collectors;

final class CoreRelevantFileEvaluator {
    private static final int MINIMUM_SCORE = 10;
    private static final int SOURCE_WINDOW_RADIUS = 120;
    private static final Set<FileType> SOURCE_TYPES = Set.of(
            FileType.JAVA, FileType.KOTLIN, FileType.JAVASCRIPT, FileType.JSX,
            FileType.TYPESCRIPT, FileType.TSX, FileType.VUE);

    RelevantFileEngine.Result evaluate(Path root, List<Path> repositoryFiles, RepositoryStructure structure,
                                       ContextReport context, TaskAnalysis task, int limit,
                                       List<SourceFileIndex> javaIndex, DependencyGraph graph) {
        List<String> warnings = new ArrayList<>();
        Map<String, SourceFileIndex> sources = indexSources(root, repositoryFiles, javaIndex, warnings);
        List<ContextDocument> contextDocuments = contextDocuments(root, repositoryFiles, task, warnings);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (SourceFileIndex source : sources.values()) {
            Candidate candidate = new Candidate(source, moduleFor(source.path(), structure.modules()),
                    task.coreTokens().size());
            lexicalEvidence(candidate, task);
            contextEvidence(candidate, contextDocuments);
            candidate.addRoleEvidence();
            candidates.put(source.path(), candidate);
        }
        applySourceAreaCohortEvidence(candidates.values());

        Map<String, String> qualifiedToPath = javaIndex.stream()
                .filter(source -> !source.qualifiedName().isBlank())
                .collect(Collectors.toMap(SourceFileIndex::qualifiedName, SourceFileIndex::path,
                        (left, right) -> left));
        Set<String> strongSeedPaths = candidates.values().stream().filter(Candidate::strongSeed)
                .map(candidate -> candidate.source.path())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        applyOneHopEvidence(candidates, graph, qualifiedToPath, strongSeedPaths);

        List<RelevantFile> relevantFiles = candidates.values().stream()
                .filter(Candidate::eligibleForResult)
                .filter(candidate -> candidate.score() >= MINIMUM_SCORE)
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(Comparator.comparingDouble(Candidate::coreCoverage).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::semanticSpecificity).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::structuralEvidenceCount).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::directScore).reversed())
                        .thenComparingInt(Candidate::passivePriority)
                        .thenComparingInt(Candidate::rolePriority)
                        .thenComparing(candidate -> candidate.source.path()))
                .limit(limit).map(Candidate::toRelevantFile).toList();
        return new RelevantFileEngine.Result(relevantFiles, aggregateModules(relevantFiles),
                aggregateSourceAreas(relevantFiles, sources), relatedFlow(relevantFiles, graph, qualifiedToPath), warnings);
    }

    private Map<String, SourceFileIndex> indexSources(Path root, List<Path> repositoryFiles,
                                                       List<SourceFileIndex> javaIndex, List<String> warnings) {
        Map<String, SourceFileIndex> sources = new LinkedHashMap<>();
        javaIndex.stream().sorted(Comparator.comparing(SourceFileIndex::path))
                .forEach(source -> sources.put(source.path(), source));
        for (Path file : repositoryFiles.stream().sorted().toList()) {
            String relative = ScanSupport.relative(root, file);
            FileType type = RepositoryScanner.classify(file.getFileName().toString());
            if (!SOURCE_TYPES.contains(type) || type == FileType.JAVA || sources.containsKey(relative)) continue;
            String content = ScanSupport.readText(root, file, warnings);
            if (content.isBlank()) continue;
            sources.put(relative, new SourceFileIndex(relative, type, "", fileStem(relative), parent(relative),
                    JavaRole.OTHER, Set.of(), Set.of(), content));
        }
        return sources;
    }

    private void lexicalEvidence(Candidate candidate, TaskAnalysis task) {
        SourceFileIndex source = candidate.source;
        List<String> structuralTerms = structuralTerms(task);
        String className = source.javaSource() ? source.className() : "";
        List<String> identityMatches = matchedTerms(className + " " + fileStem(source.path()), structuralTerms);
        if (!identityMatches.isEmpty()) candidate.add("CLASS_OR_FILE", 30,
                "Class or file name matched core/exact task term: " + String.join(", ", identityMatches));
        String location = source.javaSource() ? source.packageName() : parent(source.path());
        List<String> locationMatches = matchedTerms(location, structuralTerms);
        if (!locationMatches.isEmpty()) candidate.add("SOURCE_AREA", 15,
                "Package or source directory matched core/exact task term: " + String.join(", ", locationMatches));
        List<String> imports = source.imports().stream()
                .filter(value -> !matchedTerms(value, structuralTerms).isEmpty()).sorted().toList();
        if (!imports.isEmpty()) candidate.add("IMPORT", 10, "Imported type matched: " + imports.get(0));

        WindowMatch match = bestWindow(source.content(), task);
        candidate.matchedCoreCount = match.coreTokens().size();
        match.coreTokens().forEach(core -> candidate.add("CORE_TOKEN", 5, "Core token matched: " + core));
        if (match.coreTokens().size() >= 2) candidate.add("MULTI_CORE", 10,
                "Multi-core match: " + match.coreTokens().size() + "/" + task.coreTokens().size());
        if (task.coreTokens().size() >= 2 && match.coreTokens().size() == task.coreTokens().size()) {
            candidate.add("FULL_CORE_COVERAGE", 10,
                    "Full core token coverage: " + match.coreTokens().size() + "/" + task.coreTokens().size());
        } else if (!match.coreTokens().isEmpty() && task.coreTokens().size() >= 2) {
            List<String> missing = task.coreTokens().stream().filter(token -> !match.coreTokens().contains(token)).toList();
            candidate.add("MISSING_CORE", 0, "Missing core tokens: " + String.join(", ", missing));
        }
        if (!match.exactPhrases().isEmpty()) candidate.add("EXACT_PHRASE", 15,
                "Exact task phrase matched: " + String.join(", ", match.exactPhrases()));
        if (!match.derivedTokens().isEmpty()) candidate.add("DERIVED_NGRAM",
                Math.min(5, match.derivedTokens().size()),
                "Derived keyword matched: " + String.join(", ", match.derivedTokens()));
    }

    private WindowMatch bestWindow(String content, TaskAnalysis task) {
        if (content == null || content.isBlank()) return WindowMatch.empty();
        WindowMatch best = WindowMatch.empty();
        for (String line : normalizeText(content).lines().toList()) {
            if (line.isBlank()) continue;
            for (String anchor : task.searchableTerms()) {
                String normalizedAnchor = normalizeText(anchor);
                int from = 0;
                while (!normalizedAnchor.isEmpty() && from < line.length()) {
                    int match = line.indexOf(normalizedAnchor, from);
                    if (match < 0) break;
                    int start = Math.max(0, match - SOURCE_WINDOW_RADIUS);
                    int end = Math.min(line.length(), match + normalizedAnchor.length() + SOURCE_WINDOW_RADIUS);
                    WindowMatch current = matchWindow(line.substring(start, end), task);
                    if (current.compareTo(best) > 0) best = current;
                    from = match + Math.max(1, normalizedAnchor.length());
                }
            }
        }
        return best;
    }

    private WindowMatch matchWindow(String window, TaskAnalysis task) {
        List<String> core = task.coreTokens().stream().filter(token -> containsTerm(window, token)).toList();
        List<String> exact = task.exactPhrases().stream().filter(phrase -> containsTerm(window, phrase)).toList();
        List<String> derived = task.derivedTokens().stream().filter(token -> containsTerm(window, token)).limit(5).toList();
        return new WindowMatch(core, exact, derived, task.coreTokens().size());
    }

    private void contextEvidence(Candidate candidate, List<ContextDocument> documents) {
        List<String> identities = new ArrayList<>();
        identities.add(candidate.source.className());
        identities.add(fileStem(candidate.source.path()));
        if (!candidate.source.packageName().isBlank()) {
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
                .filter(candidate -> candidate.strongSeed() && candidate.cohortEligible())
                .collect(Collectors.groupingBy(Candidate::businessArea, LinkedHashMap::new, Collectors.toList()));
        int largestArea = areas.values().stream().mapToInt(List::size).max().orElse(0);
        for (Map.Entry<String, List<Candidate>> entry : areas.entrySet()) {
            if (entry.getValue().size() < 2 || entry.getValue().size() < largestArea) continue;
            entry.getValue().forEach(candidate -> candidate.add("SOURCE_AREA_COHORT", 5,
                    "Strong task evidence is concentrated in source area: " + entry.getKey()));
        }
    }

    private void applyOneHopEvidence(Map<String, Candidate> candidates, DependencyGraph graph,
                                     Map<String, String> qualifiedToPath, Set<String> strongSeedPaths) {
        for (DependencyEdge edge : graph.edges().stream()
                .sorted(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to)).toList()) {
            String fromPath = qualifiedToPath.get(edge.from());
            String toPath = qualifiedToPath.get(edge.to());
            if (fromPath == null || toPath == null || fromPath.equals(toPath)) continue;
            if (strongSeedPaths.contains(toPath)) {
                Candidate dependent = candidates.get(fromPath);
                if (dependent != null) dependent.addOnce("DEPENDENCY", 10,
                        "Field or constructor dependency on strong relevant type: " + simpleName(edge.to()));
            }
            if (strongSeedPaths.contains(fromPath)) {
                Candidate dependency = candidates.get(toPath);
                if (dependency != null && !dependency.has("DEPENDENCY")) dependency.addOnce("ARCHITECTURE_NEIGHBOR", 5,
                        "One-hop dependency neighbor of strong relevant type: " + simpleName(edge.from()));
            }
        }
    }

    private List<ContextDocument> contextDocuments(Path root, List<Path> repositoryFiles, TaskAnalysis task,
                                                    List<String> warnings) {
        List<String> meaningfulTerms = structuralTerms(task);
        List<ContextDocument> documents = new ArrayList<>();
        for (Path file : repositoryFiles.stream().sorted().toList()) {
            String relative = ScanSupport.relative(root, file);
            if (!relative.equalsIgnoreCase("README.md") && !relative.equalsIgnoreCase("AGENTS.md")) continue;
            String content = ScanSupport.readText(root, file, warnings);
            if (meaningfulTerms.stream().anyMatch(term -> containsTerm(content, term))) {
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
                        .thenComparing(RelevantModule::path)).toList();
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
                        .thenComparing(RelevantSourceArea::name)).toList();
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
                .filter(edge -> paths.contains(qualifiedToPath.get(edge.from()))
                        && paths.contains(qualifiedToPath.get(edge.to())))
                .sorted(Comparator.comparing(DependencyEdge::from).thenComparing(DependencyEdge::to)).toList();
    }

    private static List<String> structuralTerms(TaskAnalysis task) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(task.exactPhrases());
        terms.addAll(task.coreTokens());
        return List.copyOf(terms);
    }

    private static List<String> matchedTerms(String value, List<String> terms) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = normalizeCompact(value);
        return terms.stream().filter(term -> {
            String normalizedTerm = normalizeCompact(term);
            return normalizedTerm.length() >= 2 && normalized.contains(normalizedTerm);
        }).distinct().limit(4).toList();
    }

    private static String moduleFor(String path, List<String> modules) {
        return modules.stream().filter(module -> !module.equals(".")
                        && (path.equals(module) || path.startsWith(module + "/")))
                .max(Comparator.comparingInt(String::length)).orElse(".");
    }

    private static boolean containsTerm(String value, String term) {
        return value != null && term != null && normalizeText(value).contains(normalizeText(term));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String normalizeCompact(String value) {
        return normalizeText(value).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String fileStem(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
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

    private static final class Candidate {
        private final SourceFileIndex source;
        private final String module;
        private final int totalCoreTokens;
        private final List<MatchEvidence> evidence = new ArrayList<>();
        private int matchedCoreCount;

        private Candidate(SourceFileIndex source, String module, int totalCoreTokens) {
            this.source = source;
            this.module = module;
            this.totalCoreTokens = totalCoreTokens;
        }

        private void add(String signal, int points, String detail) { evidence.add(new MatchEvidence(signal, points, detail)); }
        private void addOnce(String signal, int points, String detail) { if (!has(signal)) add(signal, points, detail); }
        private boolean has(String signal) { return evidence.stream().anyMatch(item -> item.signal().equals(signal)); }

        private void addRoleEvidence() {
            if (!hasTaskEvidence() || !source.javaSource() || source.role() == JavaRole.OTHER) return;
            int points = switch (source.role()) {
                case CONTROLLER, SERVICE -> 2;
                case REPOSITORY, MAPPER -> 1;
                case COMPONENT, OTHER -> 0;
            };
            add("JAVA_ROLE", points, "Architecture role: " + source.role());
        }

        private boolean hasTaskEvidence() {
            return evidence.stream().anyMatch(item -> Set.of("CORE_TOKEN", "EXACT_PHRASE", "DERIVED_NGRAM",
                    "CLASS_OR_FILE", "SOURCE_AREA", "IMPORT").contains(item.signal()));
        }

        private boolean strongSeed() {
            return has("EXACT_PHRASE") || has("MULTI_CORE") || has("FULL_CORE_COVERAGE")
                    || has("CLASS_OR_FILE") || has("SOURCE_AREA");
        }

        private boolean eligibleForResult() {
            return matchedCoreCount > 0 || has("EXACT_PHRASE") || has("CLASS_OR_FILE") || has("SOURCE_AREA")
                    || has("IMPORT") || has("DEPENDENCY") || has("ARCHITECTURE_NEIGHBOR");
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
            String leaf = parts.length == 0 ? "" : parts[parts.length - 1].toLowerCase(Locale.ROOT);
            return !Set.of("dto", "vo", "entity", "model", "enum", "enums", "constant", "constants",
                    "util", "utils", "config", "configuration").contains(leaf);
        }

        private int score() { return Math.min(100, evidence.stream().mapToInt(MatchEvidence::points).sum()); }
        private double coreCoverage() { return totalCoreTokens == 0 ? 0 : (double) matchedCoreCount / totalCoreTokens; }
        private int semanticSpecificity() {
            if (has("EXACT_PHRASE")) return 4;
            if (has("FULL_CORE_COVERAGE")) return 3;
            if (has("MULTI_CORE")) return 2;
            return matchedCoreCount > 0 ? 1 : 0;
        }
        private int directScore() {
            return evidence.stream().filter(item -> !item.signal().equals("DEPENDENCY")
                            && !item.signal().equals("ARCHITECTURE_NEIGHBOR"))
                    .mapToInt(MatchEvidence::points).sum();
        }
        private int structuralEvidenceCount() {
            return (int) evidence.stream().filter(item -> Set.of("CLASS_OR_FILE", "SOURCE_AREA", "IMPORT",
                    "DEPENDENCY", "ARCHITECTURE_NEIGHBOR").contains(item.signal())).count();
        }
        private int passivePriority() { return cohortEligible() ? 0 : 1; }
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
            RelevanceLevel level = score >= 50 ? RelevanceLevel.HIGH
                    : score >= 25 && mediumEligible() ? RelevanceLevel.MEDIUM : RelevanceLevel.LOW;
            Optional<String> className = source.javaSource() ? Optional.of(source.className()) : Optional.empty();
            return new RelevantFile(source.path(), className, module, score, level, evidence);
        }
        private boolean mediumEligible() {
            if (totalCoreTokens < 2 || matchedCoreCount != 1) return true;
            return structuralEvidenceCount() > 0;
        }
    }

    private record WindowMatch(List<String> coreTokens, List<String> exactPhrases,
                               List<String> derivedTokens, int totalCoreTokens) implements Comparable<WindowMatch> {
        private WindowMatch {
            coreTokens = List.copyOf(coreTokens);
            exactPhrases = List.copyOf(exactPhrases);
            derivedTokens = List.copyOf(derivedTokens);
        }
        private static WindowMatch empty() { return new WindowMatch(List.of(), List.of(), List.of(), 0); }
        private int lexicalScore() {
            int score = Math.min(20, coreTokens.size() * 5) + Math.min(5, derivedTokens.size());
            if (coreTokens.size() >= 2) score += 10;
            if (totalCoreTokens >= 2 && coreTokens.size() == totalCoreTokens) score += 10;
            if (!exactPhrases.isEmpty()) score += 15;
            return score;
        }
        @Override public int compareTo(WindowMatch other) {
            int byScore = Integer.compare(lexicalScore(), other.lexicalScore());
            if (byScore != 0) return byScore;
            int byCore = Integer.compare(coreTokens.size(), other.coreTokens.size());
            return byCore != 0 ? byCore : Integer.compare(exactPhrases.size(), other.exactPhrases.size());
        }
    }

    private static final class Aggregate {
        private int score;
        private final List<String> paths = new ArrayList<>();
        private void add(RelevantFile file) { score = Math.max(score, file.score()); paths.add(file.path()); }
    }

    private record ContextDocument(String path, String normalizedContent) {}
}
