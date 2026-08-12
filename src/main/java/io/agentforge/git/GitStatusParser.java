package io.agentforge.git;

import io.agentforge.model.ChangeStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GitStatusParser {
    private GitStatusParser() {}

    static List<Entry> parse(String output, String scope) {
        List<Entry> entries = new ArrayList<>();
        String[] tokens = output.split("\\u0000", -1);
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isEmpty() || token.length() < 3) continue;
            String porcelainStatus = token.substring(0, 2);
            String path = stripScope(token.substring(3), scope);
            boolean hasSourcePath = porcelainStatus.indexOf('R') >= 0 || porcelainStatus.indexOf('C') >= 0;
            String oldPath = null;
            if (hasSourcePath && index + 1 < tokens.length) {
                oldPath = stripScope(tokens[++index], scope);
            }
            entries.add(new Entry(porcelainStatus, path, oldPath));
        }
        entries.sort(Comparator.comparing(Entry::path)
                .thenComparing(entry -> entry.oldPath() == null ? "" : entry.oldPath()));
        return List.copyOf(entries);
    }

    static ChangeStatus collapse(String porcelainStatus) {
        if ("??".equals(porcelainStatus)) return ChangeStatus.UNTRACKED;
        if (porcelainStatus.indexOf('R') >= 0 || porcelainStatus.indexOf('C') >= 0) {
            return ChangeStatus.RENAMED;
        }
        if (porcelainStatus.indexOf('D') >= 0) return ChangeStatus.DELETED;
        if (porcelainStatus.indexOf('A') >= 0) return ChangeStatus.ADDED;
        return ChangeStatus.MODIFIED;
    }

    private static String stripScope(String gitPath, String scope) {
        String normalized = gitPath.replace('\\', '/');
        if (".".equals(scope)) return normalized;
        String prefix = scope.endsWith("/") ? scope : scope + "/";
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
    }

    record Entry(String porcelainStatus, String path, String oldPath) {
        Entry {
            if (porcelainStatus == null || porcelainStatus.length() != 2) {
                throw new IllegalArgumentException("Git porcelain status must contain two characters");
            }
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Git status path is required");
        }

        boolean untracked() {
            return "??".equals(porcelainStatus);
        }

        boolean rename() {
            return porcelainStatus.indexOf('R') >= 0;
        }
    }
}
