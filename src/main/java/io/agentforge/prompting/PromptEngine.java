package io.agentforge.prompting;

import io.agentforge.model.AcceptanceCriterion;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.EngineeringPrompt;
import io.agentforge.model.GitBaseline;
import io.agentforge.model.PlanConstraint;
import io.agentforge.model.PlanStep;
import io.agentforge.model.PlanTest;
import io.agentforge.model.PromptScopeFile;
import io.agentforge.model.PromptScopeSection;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.TaskType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PromptEngine {
    public EngineeringPrompt create(EngineeringPlan plan) {
        return new EngineeringPrompt(
                plan.task(),
                plan.taskType(),
                plan.confidence(),
                plan.repository(),
                plan.gitBaseline(),
                objective(plan.taskType(), plan.reliableScope()),
                primary(plan),
                secondary(plan),
                reference(plan),
                architectureInstructions(plan),
                policyInstructions(plan),
                doNotTouchPaths(plan),
                workingTreeInstructions(plan),
                changeInstructions(plan),
                plan.preImplementationRisk(),
                riskInstructions(plan),
                verificationInstructions(plan),
                acceptanceCriteria(plan),
                distinct(plan.warnings()),
                List.of(
                        "Root Cause / Findings",
                        "Files Read",
                        "Files Modified",
                        "Why Each File Was Modified",
                        "Tests Executed",
                        "Test Results",
                        "AgentForge Diff Result",
                        "Remaining Risks",
                        "Scope Deviations"),
                List.of(
                        "If a SECONDARY file was modified, explain why the modification was necessary.",
                        "If a REFERENCE file was modified, explain why read-only context had to be changed.",
                        "If a file outside PRIMARY, SECONDARY, and REFERENCE scope was added or modified, mark it as SCOPE DEVIATION and explain why.",
                        "If no source files were modified, report Files Modified as None and AgentForge Diff Result as Not run — no changes."));
    }

    private String objective(TaskType type, boolean reliableScope) {
        if (!reliableScope) {
            return "Establish a reliable implementation scope before making any code changes.";
        }
        return switch (type) {
            case BUGFIX -> "Reproduce or trace the failure, identify the root cause, and implement the minimum safe fix.";
            case FEATURE -> "Identify the existing extension point and implement the smallest compatible feature path.";
            case INTEGRATION -> "Trace the existing integration boundary and implement through existing adapters, services, and contracts.";
            case REFACTOR -> "Preserve externally observable behavior while keeping the refactor bounded to the planned scope.";
            case ANALYSIS -> "Trace the relevant flow and report verified findings without modifying source code by default.";
            case UNKNOWN -> "Inspect the planned scope and establish the required change before modifying code.";
        };
    }

    private PromptScopeSection primary(EngineeringPlan plan) {
        return new PromptScopeSection(
                "PRIMARY — likely change targets",
                "Likely modification scope.",
                scopeFiles(plan.scope().primary()),
                plan.reliableScope()
                        ? List.of(
                                "Read and analyze PRIMARY files first.",
                                "Modify a PRIMARY file only when task evidence supports the change.")
                        : List.of("Do not modify code until a reliable PRIMARY scope is established."));
    }

    private PromptScopeSection secondary(EngineeringPlan plan) {
        return new PromptScopeSection(
                "SECONDARY — inspect before changing",
                "Inspect before changing.",
                scopeFiles(plan.scope().secondary()),
                List.of("Do not modify SECONDARY files unless analysis of PRIMARY files demonstrates that the change is necessary."));
    }

    private PromptScopeSection reference(EngineeringPlan plan) {
        return new PromptScopeSection(
                "REFERENCE — read-only context",
                "Read-only context by default.",
                scopeFiles(plan.scope().reference()),
                List.of(
                        "Treat REFERENCE files as read-only context.",
                        "Do not modify them unless direct implementation evidence proves that the task cannot be completed safely without doing so."));
    }

    private List<PromptScopeFile> scopeFiles(List<RelevantFile> files) {
        return files.stream().map(file -> new PromptScopeFile(fileName(file.path()), file.path())).toList();
    }

    private List<String> architectureInstructions(EngineeringPlan plan) {
        List<String> result = new ArrayList<>();
        if (plan.repository().architecture().contractConfigured()) {
            result.add("Configured Architecture Contract");
            constraints(plan, PlanConstraint.Kind.ARCHITECTURE_LAYER)
                    .forEach(layer -> result.add("Layer: " + layer));
            constraints(plan, PlanConstraint.Kind.ARCHITECTURE_ALLOWED)
                    .forEach(relationship -> result.add("Allowed: " + relationship));
            result.addAll(forbiddenRelationships(plan));
            constraints(plan, PlanConstraint.Kind.ARCHITECTURE_UNDECLARED)
                    .forEach(relationship -> result.add("Rule not declared: " + relationship));
            result.add("Do not introduce dependencies that violate the configured architecture contract.");
        } else {
            result.add("Architecture contract is not configured.");
            result.add("Observed dependency flows are lightweight evidence only.");
            result.add("Do not treat them as authoritative architecture rules.");
            result.add("Do not invent architectural constraints.");
            constraints(plan, PlanConstraint.Kind.OBSERVED_FLOW)
                    .forEach(flow -> result.add("Observed flow: " + flow));
        }
        return List.copyOf(result);
    }

    private List<String> forbiddenRelationships(EngineeringPlan plan) {
        List<String> layers = constraints(plan, PlanConstraint.Kind.ARCHITECTURE_LAYER);
        Map<String, List<String>> allowedByLayer = new LinkedHashMap<>();
        for (String relationship : constraints(plan, PlanConstraint.Kind.ARCHITECTURE_ALLOWED)) {
            String[] parts = relationship.split(" -> ", 2);
            if (parts.length != 2) continue;
            List<String> allowed = parts[1].equals("NONE") ? List.of() : List.of(parts[1]);
            allowedByLayer.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).addAll(allowed);
        }
        List<String> result = new ArrayList<>();
        for (String layer : layers) {
            if (!allowedByLayer.containsKey(layer)) continue;
            List<String> forbidden = layers.stream()
                    .filter(candidate -> !allowedByLayer.get(layer).contains(candidate))
                    .toList();
            if (!forbidden.isEmpty()) {
                result.add("Forbidden: " + layer + " -> " + String.join(", ", forbidden));
            }
        }
        return result;
    }

    private List<String> policyInstructions(EngineeringPlan plan) {
        if (plan.defaultPolicyUsed()) {
            return List.of(
                    "Policy source: AgentForge built-in defaults.",
                    "These restrictions come from AgentForge built-in defaults because the repository does not provide .agentforge/policies.yaml.",
                    "Do not modify Do Not Touch paths unless the user explicitly requires it.");
        }
        return List.of(
                "Policy source: .agentforge/policies.yaml.",
                "Do not modify Do Not Touch paths unless the user explicitly requires it.");
    }

    private List<String> doNotTouchPaths(EngineeringPlan plan) {
        List<String> result = new ArrayList<>(constraints(plan, PlanConstraint.Kind.POLICY_DENY));
        result.addAll(constraints(plan, PlanConstraint.Kind.POLICY_PROTECTED));
        return distinct(result);
    }

    private List<String> workingTreeInstructions(EngineeringPlan plan) {
        GitBaseline baseline = plan.gitBaseline();
        if (!baseline.available()) {
            return List.of(
                    "Git baseline is unavailable.",
                    "Do not assume that the working tree is clean.");
        }
        if (baseline.workingTree() == GitBaseline.WorkingTreeState.CLEAN) {
            return List.of("The repository working tree is clean.");
        }

        List<String> result = new ArrayList<>();
        result.add("The repository already contains " + baseline.changedFiles() + " changed "
                + (baseline.changedFiles() == 1 ? "file." : "files."));
        if (plan.scopeOverlap().changedRelevantFiles() > 0) {
            result.add(plan.scopeOverlap().changedRelevantFiles() + " existing changed "
                    + (plan.scopeOverlap().changedRelevantFiles() == 1 ? "file overlaps" : "files overlap")
                    + " the planned scope.");
        }
        if (plan.scopeOverlap().primaryOverlap()) result.add("PRIMARY overlap is present.");
        else if (plan.scopeOverlap().secondaryOverlap()) result.add("SECONDARY overlap is present.");
        result.add("Do not reset existing changes.");
        result.add("Do not discard existing changes.");
        result.add("Do not overwrite unrelated work.");
        result.add("Inspect the existing diff before modifying overlapping files.");
        result.add("Preserve changes unrelated to this task.");
        result.add("Do not use git checkout -- <file>.");
        result.add("Do not use git restore <file>.");
        result.add("Do not use git reset --hard.");
        result.add("Do not clean untracked files.");
        if (plan.scopeOverlap().primaryOverlap()) {
            result.add("Before modifying any overlapping PRIMARY file, inspect its existing diff and preserve unrelated edits.");
        }
        return List.copyOf(result);
    }

    private List<String> changeInstructions(EngineeringPlan plan) {
        List<String> result = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (plan.taskType() == TaskType.ANALYSIS
                    && (step.description().equals("Execute the generated Test Plan.")
                    || step.description().equals("Run agentforge diff after implementation."))) {
                continue;
            }
            result.add(step.description());
        }
        if (plan.taskType() == TaskType.BUGFIX) result.add("Avoid unrelated refactoring.");
        if (plan.taskType() == TaskType.FEATURE) result.add("Do not refactor unrelated modules.");
        return distinct(result);
    }

    private List<String> riskInstructions(EngineeringPlan plan) {
        return switch (plan.preImplementationRisk().level()) {
            case HIGH -> List.of(
                    "Proceed conservatively.",
                    "Do not expand scope without direct evidence.",
                    "Inspect overlapping changes before editing.",
                    "Prefer a minimum-diff implementation.");
            case MEDIUM -> List.of("Keep implementation bounded to the planned scope and known constraints.");
            case LOW -> List.of("Follow the planned scope and known constraints.");
        };
    }

    private List<String> verificationInstructions(EngineeringPlan plan) {
        List<String> result = new ArrayList<>();
        for (PlanTest test : plan.tests()) {
            String description = test.description();
            if (plan.taskType() == TaskType.ANALYSIS && requiresModification(description)) {
                result.add("If code is modified, " + lowerFirst(description));
            } else {
                result.add(description);
            }
        }
        return distinct(result);
    }

    private boolean requiresModification(String description) {
        return description.equals("Run Maven tests.")
                || description.equals("Run Gradle tests.")
                || description.equals("Run the project test command.")
                || description.equals("Run repository-native tests if available.")
                || description.equals("Run agentforge diff after implementation.");
    }

    private String lowerFirst(String value) {
        if (value.isEmpty()) return value;
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private List<String> acceptanceCriteria(EngineeringPlan plan) {
        List<String> result = new ArrayList<>();
        for (AcceptanceCriterion criterion : plan.acceptanceCriteria()) {
            String description = criterion.description();
            if (plan.taskType() == TaskType.ANALYSIS
                    && description.equals("Relevant task behavior is implemented or verified.")) {
                result.add("Relevant task behavior or flow is traced and findings are verified.");
            } else if (plan.taskType() == TaskType.ANALYSIS && description.equals("Tests pass.")) {
                result.add("If code is modified, relevant tests pass.");
            } else {
                result.add(description);
            }
        }
        return distinct(result);
    }

    private List<String> constraints(EngineeringPlan plan, PlanConstraint.Kind kind) {
        return plan.constraints().stream()
                .filter(constraint -> constraint.kind() == kind)
                .map(PlanConstraint::description)
                .toList();
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String fileName(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
