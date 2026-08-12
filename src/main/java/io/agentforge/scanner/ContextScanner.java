package io.agentforge.scanner;

import io.agentforge.model.ContextReport;
import io.agentforge.model.FileType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class ContextScanner {
    private static final List<String> CONTEXT_ROOTS = List.of("docs/", "skills/", ".skill/", "agents/", ".agent/");

    public ContextReport scan(Path root, List<Path> repositoryFiles) {
        List<String> warnings = new ArrayList<>();
        Map<String, Set<String>> evidenceSets = new LinkedHashMap<>();
        Path agents = root.resolve("AGENTS.md");
        boolean agentsPresent = Files.isRegularFile(agents);
        int agentsLines = agentsPresent ? lineCount(ScanSupport.readText(root, agents, warnings)) : 0;
        boolean readme = repositoryFiles.stream().anyMatch(file -> isRootFile(root, file, "README.md"));
        boolean docs = Files.isDirectory(root.resolve("docs"));
        boolean architectureDoc = false;
        int skills = 0;
        int agentRules = 0;

        List<Path> contextFiles = new ArrayList<>();
        for (Path file : repositoryFiles) {
            String relative = ScanSupport.relative(root, file);
            String lower = relative.toLowerCase(Locale.ROOT);
            String name = file.getFileName().toString();
            if (name.equalsIgnoreCase("SKILL.md")) skills++;
            if (name.equalsIgnoreCase("AGENTS.md") || name.equalsIgnoreCase("CLAUDE.md")
                    || lower.startsWith("agents/") || lower.startsWith(".agent/")) agentRules++;
            if (lower.startsWith("docs/architecture/") || (lower.startsWith("docs/") && lower.contains("architecture"))) {
                architectureDoc = true;
                addEvidence(evidenceSets, "architecture", relative);
            }
            if (isContextFile(relative, name)) contextFiles.add(file);
        }

        scanRuleEvidence(root, contextFiles, warnings, evidenceSets, "git",
                text -> containsAny(text, "git rule", "git workflow", "branch", "commit", "版本控制", "分支规范", "提交规范"));
        scanRuleEvidence(root, contextFiles, warnings, evidenceSets, "database",
                text -> containsAny(text, "database rule", "migration", "schema change", "database change", "数据库", "迁移规则"));
        scanRuleEvidence(root, contextFiles, warnings, evidenceSets, "api",
                text -> containsAny(text, "api rule", "api contract", "backward compatibility", "breaking change", "接口契约", "api 规范"));
        scanRuleEvidence(root, contextFiles, warnings, evidenceSets, "testing",
                text -> containsAny(text, "test rule", "testing rule", "run tests", "mvn test", "npm test", "测试规则", "运行测试"));

        Map<String, List<String>> evidence = new LinkedHashMap<>();
        evidenceSets.forEach((key, values) -> evidence.put(key, List.copyOf(values)));
        return new ContextReport(
                agentsPresent, agentsLines, readme, architectureDoc, docs, skills, agentRules,
                evidence.containsKey("git"), evidence.containsKey("database"), evidence.containsKey("api"),
                evidence.containsKey("testing"), evidence, warnings);
    }

    private boolean isContextFile(String relative, String name) {
        if (RepositoryScanner.classify(name) != FileType.MARKDOWN) return false;
        if (name.equalsIgnoreCase("AGENTS.md") || name.equalsIgnoreCase("CLAUDE.md") || name.equalsIgnoreCase("README.md")) return true;
        String normalized = relative.toLowerCase(Locale.ROOT);
        return CONTEXT_ROOTS.stream().anyMatch(normalized::startsWith);
    }

    private void scanRuleEvidence(Path root, List<Path> files, List<String> warnings,
                                  Map<String, Set<String>> evidence, String key, Predicate<String> predicate) {
        for (Path file : files) {
            String text = ScanSupport.readText(root, file, warnings).toLowerCase(Locale.ROOT);
            if (predicate.test(text)) addEvidence(evidence, key, ScanSupport.relative(root, file));
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }

    private static void addEvidence(Map<String, Set<String>> evidence, String key, String path) {
        evidence.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(path);
    }

    private boolean isRootFile(Path root, Path file, String name) {
        return file.getParent() != null && file.getParent().equals(root) && file.getFileName().toString().equalsIgnoreCase(name);
    }

    private int lineCount(String text) {
        if (text.isEmpty()) return 0;
        return text.split("\\R", -1).length;
    }
}
