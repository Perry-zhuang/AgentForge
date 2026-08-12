package io.agentforge.model;

public record PolicyViolation(ViolationSeverity severity, String rule, String path, String message) {}
