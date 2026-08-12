package io.agentforge.model;

import java.util.Locale;

public enum FileType {
    JAVA, KOTLIN, JAVASCRIPT, JSX, TYPESCRIPT, TSX, VUE, SQL, YAML, MARKDOWN, SHELL, OTHER;

    public static FileType classify(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return JAVA;
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return KOTLIN;
        if (lower.endsWith(".jsx")) return JSX;
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) return JAVASCRIPT;
        if (lower.endsWith(".tsx")) return TSX;
        if (lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts")) return TYPESCRIPT;
        if (lower.endsWith(".vue")) return VUE;
        if (lower.endsWith(".sql")) return SQL;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return YAML;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return MARKDOWN;
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")) return SHELL;
        return OTHER;
    }

    public boolean source() {
        return switch (this) {
            case JAVA, KOTLIN, JAVASCRIPT, JSX, TYPESCRIPT, TSX, VUE -> true;
            default -> false;
        };
    }
}
