package io.agentforge.intent;

import io.agentforge.model.ChangeExpectation;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.ExpectedChange;
import io.agentforge.model.IntentConsistencyReport;
import io.agentforge.model.IntentConsistencyReport.IntentConsistencyViolation;
import io.agentforge.model.IntentConsistencyReport.IntentConsistencyViolation.Code;
import io.agentforge.model.MutationMode;
import io.agentforge.model.TaskSpec;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Pure comparison of a frozen TaskSpec with execution-only delta facts. */
@Component
public class IntentDeltaConsistencyCheck {
    public IntentConsistencyReport check(TaskSpec spec, ExecutionChangeReport delta,
                                         VerificationReport scopeVerification) {
        if (spec == null || delta == null || scopeVerification == null) {
            throw new IllegalArgumentException("TaskSpec, execution delta, and scope verification are required");
        }
        List<String> unexpected = scopeVerification.findings().stream()
                .filter(finding -> finding.category() == VerificationFinding.Category.SCOPE
                        && finding.code().equals("OUT_OF_SCOPE_MODIFICATION"))
                .map(VerificationFinding::path).distinct().sorted().toList();
        Map<String, List<ExecutionChangeReport.ExecutionChange>> byPath = index(delta);
        List<IntentConsistencyViolation> violations = new ArrayList<>();
        for (ExpectedChange expected : spec.expectedChanges()) compare(expected, byPath, violations);
        if (scopeVerification.executionBoundary().mutationMode() == MutationMode.READ_ONLY
                && !delta.changes().isEmpty()) {
            delta.changes().stream().map(ExecutionChangeReport.ExecutionChange::path).distinct().sorted()
                    .forEach(path -> violations.add(new IntentConsistencyViolation(Code.READ_ONLY_MUTATION, path,
                            "READ_ONLY intent permits no execution mutation.")));
        }
        VerificationStatus status = unexpected.isEmpty() && violations.isEmpty()
                ? VerificationStatus.PASS : VerificationStatus.FAIL;
        return new IntentConsistencyReport(spec, status, delta.changes().size(), unexpected, violations);
    }

    private Map<String, List<ExecutionChangeReport.ExecutionChange>> index(ExecutionChangeReport delta) {
        Map<String, List<ExecutionChangeReport.ExecutionChange>> result = new HashMap<>();
        for (var change : delta.changes()) {
            result.computeIfAbsent(change.path(), ignored -> new ArrayList<>()).add(change);
            if (change.oldPath() != null) result.computeIfAbsent(change.oldPath(), ignored -> new ArrayList<>()).add(change);
        }
        return result;
    }

    private void compare(ExpectedChange expected,
                         Map<String, List<ExecutionChangeReport.ExecutionChange>> changes,
                         List<IntentConsistencyViolation> violations) {
        List<ExecutionChangeReport.ExecutionChange> actual = changes.getOrDefault(expected.path(), List.of());
        boolean changed = !actual.isEmpty();
        if (expected.expectation() == ChangeExpectation.NO_CHANGE && changed) {
            violations.add(new IntentConsistencyViolation(Code.FORBIDDEN_CHANGE, expected.path(),
                    "Path marked NO_CHANGE was changed."));
        } else if (expected.expectation() == ChangeExpectation.MODIFY
                && actual.stream().noneMatch(change -> change.type() == ExecutionChangeReport.ChangeType.MODIFIED)) {
            violations.add(new IntentConsistencyViolation(Code.EXPECTED_MODIFY_MISSING, expected.path(),
                    "Expected MODIFY did not occur."));
        } else if (expected.expectation() == ChangeExpectation.CREATE
                && actual.stream().noneMatch(change -> change.type() == ExecutionChangeReport.ChangeType.ADDED)) {
            violations.add(new IntentConsistencyViolation(Code.EXPECTED_CREATE_MISSING, expected.path(),
                    "Expected CREATE did not occur."));
        } else if (expected.expectation() == ChangeExpectation.DELETE
                && actual.stream().noneMatch(change -> change.type() == ExecutionChangeReport.ChangeType.DELETED)) {
            violations.add(new IntentConsistencyViolation(Code.EXPECTED_DELETE_MISSING, expected.path(),
                    "Expected DELETE did not occur."));
        }
    }
}
