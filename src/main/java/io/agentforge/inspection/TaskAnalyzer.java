package io.agentforge.inspection;

import io.agentforge.model.TaskAnalysis;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TaskAnalyzer {
    private static final int MAX_DERIVED_TOKENS = 24;
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CJK_SEQUENCE = Pattern.compile("[\\p{IsHan}]+");
    private static final Set<String> ASCII_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "that", "this", "analyze", "analysis",
            "implement", "implementation", "change", "update", "feature", "code", "file", "files",
            "service", "controller", "repository", "mapper", "component", "class", "impl", "sys", "api",
            "dto", "dao", "entity", "model", "util", "utils", "common");
    private static final List<String> CJK_STOP_WORDS = List.of(
            "分析", "实现", "修改", "新增", "添加", "优化", "排查", "查看", "代码", "文件", "功能",
            "相关", "这个", "一个", "以及", "并且", "和", "与", "的");

    public TaskAnalysis analyze(String task) {
        if (task == null || task.isBlank()) return new TaskAnalysis(List.of(), List.of(), List.of());
        String normalized = Normalizer.normalize(task, Normalizer.Form.NFKC);
        LinkedHashSet<String> core = new LinkedHashSet<>();
        LinkedHashSet<String> exact = new LinkedHashSet<>();
        LinkedHashSet<String> derived = new LinkedHashSet<>();
        addAsciiTerms(normalized, core, exact, derived);
        addCjkTerms(normalized, core, exact, derived);
        derived.removeAll(core);
        derived.removeAll(exact);
        return new TaskAnalysis(List.copyOf(core), List.copyOf(exact),
                derived.stream().limit(MAX_DERIVED_TOKENS).toList());
    }

    private void addAsciiTerms(String input, Set<String> core, Set<String> exact, Set<String> derived) {
        LinkedHashSet<String> asciiCore = new LinkedHashSet<>();
        Matcher compositeMatcher = ASCII_IDENTIFIER.matcher(input);
        while (compositeMatcher.find()) {
            String raw = compositeMatcher.group();
            if (raw.contains("_") || raw.contains("-") || raw.matches(".*[a-z0-9][A-Z].*")) {
                addAscii(normalizeAscii(raw), exact);
            }
            List<String> parts = asciiParts(raw);
            parts.forEach(part -> {
                addAscii(part, core);
                addAscii(part, asciiCore);
            });
            if (parts.size() > 1) addAscii(String.join("", parts), exact);
        }
        List<String> orderedCore = new ArrayList<>(asciiCore);
        if (orderedCore.size() > 1) exact.add(String.join(" ", orderedCore));
        for (int index = 0; index + 1 < orderedCore.size(); index++) {
            addAscii(orderedCore.get(index) + orderedCore.get(index + 1), derived);
        }
    }

    private List<String> asciiParts(String raw) {
        String separated = raw.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .replace('_', ' ').replace('-', ' ');
        List<String> parts = new ArrayList<>();
        for (String part : separated.split("\\s+")) {
            String normalized = normalizeAscii(part);
            if (normalized.length() >= 3 && !ASCII_STOP_WORDS.contains(normalized)) parts.add(normalized);
        }
        return parts;
    }

    private void addAscii(String value, Set<String> terms) {
        String normalized = normalizeAscii(value);
        if (normalized.length() >= 3 && !ASCII_STOP_WORDS.contains(normalized)) terms.add(normalized);
    }

    private String normalizeAscii(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void addCjkTerms(String input, Set<String> core, Set<String> exact, Set<String> derived) {
        String withoutStopWords = input;
        for (String stopWord : CJK_STOP_WORDS) withoutStopWords = withoutStopWords.replace(stopWord, " ");
        Matcher matcher = CJK_SEQUENCE.matcher(withoutStopWords);
        while (matcher.find()) {
            String sequence = matcher.group();
            if (sequence.length() < 2) continue;
            exact.add(sequence);
            List<String> sequenceCore = splitCjkCore(sequence);
            core.addAll(sequenceCore);
            for (int index = 0; index + 1 < sequenceCore.size(); index++) {
                derived.add(sequenceCore.get(index) + sequenceCore.get(index + 1));
            }
            for (int size = Math.min(4, sequence.length()); size >= 2; size--) {
                for (int start = 0; start + size <= sequence.length(); start++) {
                    derived.add(sequence.substring(start, start + size));
                }
            }
        }
    }

    private List<String> splitCjkCore(String sequence) {
        if (sequence.length() <= 3) return List.of(sequence);
        List<String> tokens = new ArrayList<>();
        int endOfPairs = sequence.length() % 2 == 0 ? sequence.length() : sequence.length() - 3;
        for (int index = 0; index < endOfPairs; index += 2) {
            tokens.add(sequence.substring(index, index + 2));
        }
        if (sequence.length() % 2 != 0) tokens.add(sequence.substring(sequence.length() - 3));
        return tokens;
    }
}
