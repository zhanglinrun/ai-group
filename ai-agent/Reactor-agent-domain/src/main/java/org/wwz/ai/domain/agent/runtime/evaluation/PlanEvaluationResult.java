package org.wwz.ai.domain.agent.runtime.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, auditable result of one quality gate.
 */
public record PlanEvaluationResult(
        boolean enabled,
        boolean accepted,
        int overallScore,
        int ruleScore,
        Integer llmScore,
        int completenessScore,
        int factualConsistencyScore,
        int toolEvidenceScore,
        List<String> failureReasons,
        String replanInstruction,
        boolean llmJudgeUsed,
        boolean budgetExhausted,
        int estimatedTokensUsed
) {

    public PlanEvaluationResult {
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        replanInstruction = replanInstruction == null ? "" : replanInstruction;
    }

    public static PlanEvaluationResult disabled() {
        return new PlanEvaluationResult(false, true, 100, 100, null,
                100, 100, 100, List.of(), "", false, false, 0);
    }

    public Map<String, Object> toPublicMap(int evaluationRound, int replanRound, int budgetUsed, int budgetLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluationRound", evaluationRound);
        result.put("replanRound", replanRound);
        result.put("accepted", accepted);
        result.put("overallScore", overallScore);
        result.put("ruleScore", ruleScore);
        if (llmScore != null) {
            result.put("llmScore", llmScore);
        }
        result.put("completenessScore", completenessScore);
        result.put("factualConsistencyScore", factualConsistencyScore);
        result.put("toolEvidenceScore", toolEvidenceScore);
        result.put("failureReasons", failureReasons);
        result.put("llmJudgeUsed", llmJudgeUsed);
        result.put("budgetExhausted", budgetExhausted);
        result.put("reflectionTokens", budgetUsed);
        result.put("reflectionTokenBudget", budgetLimit);
        return result;
    }
}
