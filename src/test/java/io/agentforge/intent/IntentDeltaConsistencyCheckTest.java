package io.agentforge.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentforge.model.ChangeExpectation;
import io.agentforge.model.EngineeringBoundarySnapshot;
import io.agentforge.model.ExecutionChangeReport;
import io.agentforge.model.ExpectedChange;
import io.agentforge.model.MutationMode;
import io.agentforge.model.TaskSpec;
import io.agentforge.model.TaskType;
import io.agentforge.model.VerificationFinding;
import io.agentforge.model.VerificationReport;
import io.agentforge.model.VerificationStatus;
import io.agentforge.model.ViolationSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentDeltaConsistencyCheckTest {
    private final IntentDeltaConsistencyCheck check = new IntentDeltaConsistencyCheck();

    @Test
    void expectedModifyCreateDeleteAndNoChangeMatchTheirActualDelta() {
        assertPass(ChangeExpectation.MODIFY, change(ExecutionChangeReport.ChangeType.MODIFIED, "target"));
        assertPass(ChangeExpectation.CREATE, change(ExecutionChangeReport.ChangeType.ADDED, "target"));
        assertPass(ChangeExpectation.DELETE, change(ExecutionChangeReport.ChangeType.DELETED, "target"));
        assertPass(ChangeExpectation.NO_CHANGE);
    }

    @Test
    void missingStrongExpectationsAndForbiddenMutationFail() {
        assertFail(ChangeExpectation.MODIFY);
        assertFail(ChangeExpectation.CREATE);
        assertFail(ChangeExpectation.DELETE);
        assertFail(ChangeExpectation.NO_CHANGE, change(ExecutionChangeReport.ChangeType.MODIFIED, "target"));
    }

    @Test
    void anyAllowedChangeAcceptsUnchangedModifiedCreatedAndDeleted() {
        assertPass(ChangeExpectation.ANY_ALLOWED_CHANGE);
        assertPass(ChangeExpectation.ANY_ALLOWED_CHANGE,
                change(ExecutionChangeReport.ChangeType.MODIFIED, "target"));
        assertPass(ChangeExpectation.ANY_ALLOWED_CHANGE,
                change(ExecutionChangeReport.ChangeType.ADDED, "target"));
        assertPass(ChangeExpectation.ANY_ALLOWED_CHANGE,
                change(ExecutionChangeReport.ChangeType.DELETED, "target"));
    }

    @Test
    void outsideBoundaryScopeFailureIsReusedAsUnexpectedFile() {
        VerificationFinding outside = new VerificationFinding(VerificationFinding.Category.SCOPE,
                ViolationSeverity.ERROR, "OUT_OF_SCOPE_MODIFICATION", "outside.txt", "outside");
        var report = check.check(spec(ChangeExpectation.ANY_ALLOWED_CHANGE),
                delta(change(ExecutionChangeReport.ChangeType.ADDED, "outside.txt")),
                verification(MutationMode.SOURCE_WRITE, List.of(outside)));

        assertThat(report.status()).isEqualTo(VerificationStatus.FAIL);
        assertThat(report.unexpectedFiles()).containsExactly("outside.txt");
    }

    @Test
    void readOnlyRequiresAnEmptyExecutionDelta() {
        assertThat(check.check(spec(ChangeExpectation.NO_CHANGE), delta(),
                verification(MutationMode.READ_ONLY, List.of())).status()).isEqualTo(VerificationStatus.PASS);
        assertThat(check.check(spec(ChangeExpectation.NO_CHANGE),
                delta(change(ExecutionChangeReport.ChangeType.MODIFIED, "target")),
                verification(MutationMode.READ_ONLY, List.of())).status()).isEqualTo(VerificationStatus.FAIL);
    }

    @Test
    void historicalDirtyAbsentFromExecutionDeltaDoesNotParticipate() {
        TaskSpec spec = new TaskSpec("id", "fix", TaskType.BUGFIX, "fix",
                List.of(new ExpectedChange("B.java", ChangeExpectation.ANY_ALLOWED_CHANGE)), List.of(), List.of());
        var report = check.check(spec,
                delta(change(ExecutionChangeReport.ChangeType.MODIFIED, "B.java")),
                verification(MutationMode.SOURCE_WRITE, List.of()));

        assertThat(report.status()).isEqualTo(VerificationStatus.PASS);
        assertThat(report.violations()).noneMatch(violation -> violation.path().equals("A.java"));
    }

    @Test
    void renameIsOneActualChangeAndCanMatchTheOldExpectedEntity() {
        var rename = new ExecutionChangeReport.ExecutionChange(ExecutionChangeReport.ChangeType.RENAMED,
                "new.java", "target", true, true);
        var report = check.check(spec(ChangeExpectation.ANY_ALLOWED_CHANGE), delta(rename),
                verification(MutationMode.SOURCE_WRITE, List.of()));
        assertThat(report.status()).isEqualTo(VerificationStatus.PASS);
        assertThat(report.actualChangedFiles()).isOne();
    }

    @Test
    void sameInputsProduceTheSameReport() {
        TaskSpec spec = spec(ChangeExpectation.MODIFY);
        ExecutionChangeReport delta = delta(change(ExecutionChangeReport.ChangeType.MODIFIED, "target"));
        VerificationReport verification = verification(MutationMode.SOURCE_WRITE, List.of());
        assertThat(check.check(spec, delta, verification)).isEqualTo(check.check(spec, delta, verification));
    }

    private void assertPass(ChangeExpectation expectation, ExecutionChangeReport.ExecutionChange... changes) {
        assertThat(check.check(spec(expectation), delta(changes),
                verification(MutationMode.SOURCE_WRITE, List.of())).status()).isEqualTo(VerificationStatus.PASS);
    }

    private void assertFail(ChangeExpectation expectation, ExecutionChangeReport.ExecutionChange... changes) {
        assertThat(check.check(spec(expectation), delta(changes),
                verification(MutationMode.SOURCE_WRITE, List.of())).status()).isEqualTo(VerificationStatus.FAIL);
    }

    private TaskSpec spec(ChangeExpectation expectation) {
        return new TaskSpec("id", "fix", TaskType.BUGFIX, "fix",
                List.of(new ExpectedChange("target", expectation)), List.of(), List.of());
    }

    private ExecutionChangeReport.ExecutionChange change(ExecutionChangeReport.ChangeType type, String path) {
        return new ExecutionChangeReport.ExecutionChange(type, path, null, true, true);
    }

    private ExecutionChangeReport delta(ExecutionChangeReport.ExecutionChange... changes) {
        return new ExecutionChangeReport("/repo", "main", "main", false, "head", "head", false,
                List.of(changes));
    }

    private VerificationReport verification(MutationMode mode, List<VerificationFinding> findings) {
        VerificationReport report = mock(VerificationReport.class);
        EngineeringBoundarySnapshot boundary = mock(EngineeringBoundarySnapshot.class);
        when(boundary.mutationMode()).thenReturn(mode);
        when(report.executionBoundary()).thenReturn(boundary);
        when(report.findings()).thenReturn(findings);
        return report;
    }
}
