package io.agentforge.model;

/** Deterministic intent semantics compared with an attributed execution delta. */
public enum ChangeExpectation {
    MODIFY,
    CREATE,
    DELETE,
    NO_CHANGE,
    ANY_ALLOWED_CHANGE
}
