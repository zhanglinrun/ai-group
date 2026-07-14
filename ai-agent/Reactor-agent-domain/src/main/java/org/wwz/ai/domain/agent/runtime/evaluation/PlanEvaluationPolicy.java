package org.wwz.ai.domain.agent.runtime.evaluation;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;

/**
 * Immutable policy for one Plan-Solve evaluation loop.
 */
public record PlanEvaluationPolicy(
        boolean enabled,
        boolean llmJudgeEnabled,
        int scoreThreshold,
        int maxReplanRounds,
        int reflectionTokenBudget,
        int judgeTimeoutSeconds,
        int maxInputChars,
        int maxJudgeResponseTokens,
        double judgeTemperature,
        String judgeModelName
) {

    private static final int DEFAULT_SCORE_THRESHOLD = 75;
    private static final int DEFAULT_MAX_REPLAN_ROUNDS = 2;
    private static final int DEFAULT_REFLECTION_TOKEN_BUDGET = 6000;
    private static final int DEFAULT_JUDGE_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_INPUT_CHARS = 12000;
    private static final int DEFAULT_MAX_JUDGE_RESPONSE_TOKENS = 600;

    public static PlanEvaluationPolicy from(ReactorConfig config) {
        if (config == null) {
            return defaults();
        }
        return new PlanEvaluationPolicy(
                Boolean.TRUE.equals(config.getEvaluatorEnabled()),
                Boolean.TRUE.equals(config.getEvaluatorLlmJudgeEnabled()),
                bounded(config.getEvaluatorScoreThreshold(), DEFAULT_SCORE_THRESHOLD, 0, 100),
                positiveOrDefault(config.getEvaluatorMaxReplanRounds(), DEFAULT_MAX_REPLAN_ROUNDS),
                positiveOrDefault(config.getEvaluatorReflectionTokenBudget(), DEFAULT_REFLECTION_TOKEN_BUDGET),
                positiveOrDefault(config.getEvaluatorJudgeTimeoutSeconds(), DEFAULT_JUDGE_TIMEOUT_SECONDS),
                positiveOrDefault(config.getEvaluatorMaxInputChars(), DEFAULT_MAX_INPUT_CHARS),
                positiveOrDefault(config.getEvaluatorMaxJudgeResponseTokens(), DEFAULT_MAX_JUDGE_RESPONSE_TOKENS),
                config.getEvaluatorTemperature() == null ? 0d : config.getEvaluatorTemperature(),
                StringUtils.defaultIfBlank(config.getEvaluatorModelName(), config.getPlannerModelName())
        );
    }

    public static PlanEvaluationPolicy defaults() {
        return new PlanEvaluationPolicy(
                true,
                true,
                DEFAULT_SCORE_THRESHOLD,
                DEFAULT_MAX_REPLAN_ROUNDS,
                DEFAULT_REFLECTION_TOKEN_BUDGET,
                DEFAULT_JUDGE_TIMEOUT_SECONDS,
                DEFAULT_MAX_INPUT_CHARS,
                DEFAULT_MAX_JUDGE_RESPONSE_TOKENS,
                0d,
                ""
        );
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static int bounded(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
