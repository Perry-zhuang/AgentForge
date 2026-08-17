package io.agentforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryResultParserTest {
    @TempDir Path root;
    private final DiscoveryResultParser parser = new DiscoveryResultParser();

    @BeforeEach void file() throws Exception { Files.writeString(root.resolve("A.java"), "class A {}"); }

    @Test void validResultAccepted() { assertThat(parser.parse(valid("A.java", "[]", "[]"), root).confidence()).isEqualTo(DiscoveryResult.Confidence.HIGH); }
    @Test void invalidJsonRejected() { assertThatThrownBy(() -> parser.parse("not-json", root)).isInstanceOf(DiscoveryException.class); }
    @Test void absolutePathRejected() { assertThatThrownBy(() -> parser.parse(valid(root.resolve("A.java").toString(), "[]", "[]"), root)).isInstanceOf(DiscoveryException.class); }
    @Test void escapingPathRejected() { assertThatThrownBy(() -> parser.parse(valid("../A.java", "[]", "[]"), root)).isInstanceOf(DiscoveryException.class); }
    @Test void duplicateScopeRejected() { assertThatThrownBy(() -> parser.parse(valid("A.java", "[\"A.java\"]", "[]"), root)).isInstanceOf(DiscoveryException.class); }
    @Test void unknownFileRejected() { assertThatThrownBy(() -> parser.parse(valid("Missing.java", "[]", "[]"), root)).isInstanceOf(DiscoveryException.class); }

    private String valid(String primary, String secondary, String reference) {
        return "{\"findings\":[{\"summary\":\"evidence\",\"evidence\":[\"" + primary
                + "\"]}],\"relevantFlows\":[],\"proposedPrimaryScope\":[\"" + primary
                + "\"],\"proposedSecondaryScope\":" + secondary + ",\"referenceFiles\":" + reference
                + ",\"risks\":[],\"unknowns\":[],\"confidence\":\"HIGH\"}";
    }
}
