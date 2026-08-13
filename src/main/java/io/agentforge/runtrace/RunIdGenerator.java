package io.agentforge.runtrace;

import java.time.Instant;

@FunctionalInterface
public interface RunIdGenerator {
    String generate(Instant startedAt);
}
