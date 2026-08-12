package io.agentforge.model;

public record DiffAuditReport(
        ChangeReport changeReport,
        PolicyReport policyReport,
        ScoreBreakdown minimalChange,
        RiskReport riskReport,
        ArchitectureReport architectureReport,
        boolean defaultPolicyUsed
) {}
