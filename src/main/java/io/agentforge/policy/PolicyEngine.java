package io.agentforge.policy;

import io.agentforge.model.ArchitectureReport;
import io.agentforge.model.ChangeReport;
import io.agentforge.model.ChangedFile;
import io.agentforge.model.PolicyConfig;
import io.agentforge.model.PolicyReport;
import io.agentforge.model.PolicyViolation;
import io.agentforge.model.ViolationSeverity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PolicyEngine {
    public PolicyReport evaluate(ChangeReport change, PolicyConfig config, ArchitectureReport architecture) {
        return evaluate(change.files(), config, architecture);
    }

    public PolicyReport evaluate(List<ChangedFile> files, PolicyConfig config, ArchitectureReport architecture) {
        return evaluate(files, config.change(), architecture);
    }

    public PolicyReport evaluate(List<ChangedFile> files, PolicyConfig.ChangePolicy changePolicy,
                                 ArchitectureReport architecture) {
        List<PolicyViolation> violations = new ArrayList<>();
        if (files.size() > changePolicy.maxFiles()) {
            violations.add(new PolicyViolation(ViolationSeverity.ERROR, "change.max_files", "-",
                    "Changed " + files.size() + " files; maximum is " + changePolicy.maxFiles()));
        }
        Set<String> emitted = new LinkedHashSet<>();
        for (ChangedFile file : files) {
            for (String path : paths(file)) {
                for (String pattern : changePolicy.deny()) {
                    addIfMatches(violations, emitted, path, pattern, "change.deny", "Denied file changed");
                }
                for (String pattern : changePolicy.protectedPaths()) {
                    addIfMatches(violations, emitted, path, pattern, "change.protected", "Protected file changed");
                }
            }
        }
        architecture.contractViolations().forEach(edge -> violations.add(new PolicyViolation(
                ViolationSeverity.WARN, "architecture.dependencies", architecture.graph().nodes().get(edge.from()).path(),
                edge.fromRole().layer() + " must not depend on " + edge.toRole().layer() + ": " + edge.from() + " -> " + edge.to())));
        return new PolicyReport(violations);
    }

    private void addIfMatches(List<PolicyViolation> violations, Set<String> emitted, String path,
                              String pattern, String rule, String message) {
        String key = rule + "|" + pattern + "|" + path;
        if (PathPolicyMatcher.matches(pattern, path) && emitted.add(key)) {
            violations.add(new PolicyViolation(ViolationSeverity.ERROR, rule, path,
                    message + " (pattern: " + pattern + ")"));
        }
    }

    private List<String> paths(ChangedFile file) {
        return file.oldPath() == null ? List.of(file.path()) : List.of(file.path(), file.oldPath());
    }
}
