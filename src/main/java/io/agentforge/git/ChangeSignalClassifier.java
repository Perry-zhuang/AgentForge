package io.agentforge.git;

import io.agentforge.scanner.RepositoryScanner;
import java.util.Collection;
import java.util.Locale;

public final class ChangeSignalClassifier {
    private ChangeSignalClassifier() {}

    public static Signals classify(Collection<String> paths) {
        boolean config = false;
        boolean dependency = false;
        boolean database = false;
        boolean security = false;
        boolean agents = false;
        boolean controller = false;
        for (String path : paths) {
            String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            String base = lower.substring(lower.lastIndexOf('/') + 1);
            config |= lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")
                    || base.startsWith(".env") || lower.contains("/config/");
            dependency |= RepositoryScanner.isBuildDescriptor(base) || base.endsWith("lock")
                    || base.equals("yarn.lock") || base.equals("pnpm-lock.yaml");
            database |= lower.endsWith(".sql") || lower.contains("/migration/") || lower.startsWith("database/")
                    || lower.contains("/db/migration/");
            security |= lower.startsWith("security/") || lower.contains("/security/");
            agents |= base.equals("agents.md") || base.equals("claude.md") || lower.startsWith(".agentforge/");
            controller |= base.endsWith("controller.java") || lower.contains("/controller/");
        }
        return new Signals(config, dependency, database, security, agents, controller);
    }

    public record Signals(
            boolean config,
            boolean dependency,
            boolean database,
            boolean security,
            boolean agents,
            boolean controller
    ) {}
}
