package io.agentforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentforge.cli.PromptRenderer;
import io.agentforge.model.TaskType;
import io.agentforge.prompting.PromptEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptRendererTest {
    private static final List<String> SECTIONS = List.of(
            "# Engineering Task", "# Repository Context", "# Objective", "# Allowed Scope",
            "# Architecture Constraints", "# Change Constraints", "# Existing Working Tree",
            "# Implementation Strategy", "# Verification", "# Acceptance Criteria", "# Final Report");

    @Test
    void rendersAllTopLevelSectionsInStableOrderWithoutInspectNoise() {
        String output = render();

        int previous = -1;
        for (String section : SECTIONS) {
            int current = output.indexOf(section);
            assertThat(current).as(section).isGreaterThan(previous);
            previous = current;
        }
        assertThat(output).doesNotContain(
                "Confidence Reason", "Core token matched", "Weak lexical recall signal", "+0", "+5");
    }

    @Test
    void producesByteIdenticalOutputAcrossThreeRuns() throws Exception {
        String first = render();
        String second = render();
        String third = render();

        assertThat(hash(first)).isEqualTo(hash(second)).isEqualTo(hash(third));
        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    private String render() {
        return new PromptRenderer().render(new PromptEngine().create(PromptTestFixtures.plan(TaskType.BUGFIX)));
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
