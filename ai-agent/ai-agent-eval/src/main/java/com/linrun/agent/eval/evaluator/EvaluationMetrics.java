package com.linrun.agent.eval.evaluator;

import java.util.Map;

public record EvaluationMetrics(
        int totalTrials,
        int passedTrials,
        int schemaFailures,
        int permissionFailures,
        int recoveryFailures,
        int quotaFailures,
        int citationRequiredTrials,
        int citationCoveredTrials,
        int toolParameterFailures,
        int privacyFailures,
        Map<String, Integer> failureCategories) {
    public EvaluationMetrics {
        failureCategories = Map.copyOf(failureCategories == null ? Map.of() : failureCategories);
    }

    public double taskSuccessRate() {
        return totalTrials == 0 ? 0.0d : (double) passedTrials / totalTrials;
    }

    public double citationCoverage() {
        return citationRequiredTrials == 0 ? 1.0d : (double) citationCoveredTrials / citationRequiredTrials;
    }
}
