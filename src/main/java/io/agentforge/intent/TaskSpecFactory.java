package io.agentforge.intent;

import io.agentforge.model.AcceptanceCriterion;
import io.agentforge.model.ChangeExpectation;
import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.ExpectedChange;
import io.agentforge.model.MutationMode;
import io.agentforge.model.RelevantFile;
import io.agentforge.model.TaskSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds a deterministic minimal intent contract from the final pre-execution plan. */
@Component
public class TaskSpecFactory {
    public TaskSpec create(EngineeringPlan plan) {
        if (plan == null) throw new IllegalArgumentException("Engineering plan is required");
        List<ExpectedChange> expected = new ArrayList<>();
        ChangeExpectation writable = plan.taskClassification().mutationMode() == MutationMode.READ_ONLY
                ? ChangeExpectation.NO_CHANGE : ChangeExpectation.ANY_ALLOWED_CHANGE;
        add(expected, plan.scope().primary(), writable);
        add(expected, plan.scope().secondary(), writable);
        add(expected, plan.scope().reference(), ChangeExpectation.NO_CHANGE);
        List<String> forbidden = List.of(
                "Files outside the frozen engineering boundary",
                "Denied or protected repository paths",
                "Paths marked NO_CHANGE");
        List<String> criteria = plan.acceptanceCriteria().stream().map(AcceptanceCriterion::description).toList();
        String task = plan.task();
        return new TaskSpec(id(task, plan.taskType().name()), task, plan.taskType(), task.strip(),
                expected, forbidden, criteria);
    }

    private void add(List<ExpectedChange> target, List<RelevantFile> files, ChangeExpectation expectation) {
        files.forEach(file -> target.add(new ExpectedChange(file.path(), expectation)));
    }

    private String id(String task, String type) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((type + "\n" + task).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
