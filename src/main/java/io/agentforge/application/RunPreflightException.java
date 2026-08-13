package io.agentforge.application;

import io.agentforge.model.EngineeringPlan;
import io.agentforge.model.RunPreflight;

public final class RunPreflightException extends RuntimeException {
    private final EngineeringPlan plan;
    private final RunPreflight preflight;

    public RunPreflightException(EngineeringPlan plan, RunPreflight preflight) {
        super("Agent run blocked by preflight: " + preflight.reason());
        if (plan == null || preflight == null || preflight.allowed()) {
            throw new IllegalArgumentException("Blocked preflight facts are required");
        }
        this.plan = plan;
        this.preflight = preflight;
    }

    public EngineeringPlan plan() { return plan; }
    public RunPreflight preflight() { return preflight; }
}
