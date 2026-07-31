package com.linrun.agent.eval.evaluator;

/** P120 initial gates. Deterministic contracts are fail-closed; LLM judgement is advisory only. */
public record EvaluationThresholds(double minimumCitationCoverage) {
    public EvaluationThresholds {
        if (minimumCitationCoverage < 0.0d || minimumCitationCoverage > 1.0d) {
            throw new IllegalArgumentException("citation coverage must be between zero and one");
        }
    }

    public static EvaluationThresholds p120Baseline() {
        return new EvaluationThresholds(0.90d);
    }
}
