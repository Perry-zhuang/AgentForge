package io.agentforge.model;

import java.util.Comparator;
import java.util.List;

/** Expected-versus-actual comparison result, kept separate from scope validation. */
public record IntentConsistencyReport(
        TaskSpec taskSpec,
        VerificationStatus status,
        int actualChangedFiles,
        List<String> unexpectedFiles,
        List<IntentConsistencyViolation> violations
) {
    public IntentConsistencyReport {
        if (taskSpec == null || status == null || actualChangedFiles < 0
                || unexpectedFiles == null || violations == null) {
            throw new IllegalArgumentException("Intent consistency facts are required");
        }
        unexpectedFiles = unexpectedFiles.stream().distinct().sorted().toList();
        violations = violations.stream().sorted(Comparator
                .comparing(IntentConsistencyViolation::path)
                .thenComparing(violation -> violation.code().ordinal())
                .thenComparing(IntentConsistencyViolation::message)).toList();
        VerificationStatus expected = unexpectedFiles.isEmpty() && violations.isEmpty()
                ? VerificationStatus.PASS : VerificationStatus.FAIL;
        if (status != expected) throw new IllegalArgumentException("Intent consistency status must be " + expected);
    }

    public record IntentConsistencyViolation(Code code, String path, String message) {
        public IntentConsistencyViolation {
            if (code == null || path == null || path.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("Intent consistency violation facts are required");
            }
        }

        public enum Code {
            EXPECTED_MODIFY_MISSING,
            EXPECTED_CREATE_MISSING,
            EXPECTED_DELETE_MISSING,
            FORBIDDEN_CHANGE,
            READ_ONLY_MUTATION
        }
    }
}
