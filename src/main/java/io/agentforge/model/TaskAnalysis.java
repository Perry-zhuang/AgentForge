package io.agentforge.model;

import java.util.LinkedHashSet;
import java.util.List;

public record TaskAnalysis(
        List<String> coreTokens,
        List<String> exactPhrases,
        List<String> derivedTokens
) {
    public TaskAnalysis {
        coreTokens = immutableDistinct(coreTokens);
        exactPhrases = immutableDistinct(exactPhrases);
        derivedTokens = immutableDistinct(derivedTokens);
    }

    public boolean empty() {
        return coreTokens.isEmpty() && exactPhrases.isEmpty() && derivedTokens.isEmpty();
    }

    public List<String> searchableTerms() {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(exactPhrases);
        terms.addAll(coreTokens);
        terms.addAll(derivedTokens);
        return List.copyOf(terms);
    }

    private static List<String> immutableDistinct(List<String> values) {
        if (values == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
