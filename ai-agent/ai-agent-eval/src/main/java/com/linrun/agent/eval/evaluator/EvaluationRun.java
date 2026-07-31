package com.linrun.agent.eval.evaluator;

import com.linrun.agent.eval.dataset.EvalDataset;
import com.linrun.agent.eval.judge.JudgeOutcome;

import java.util.List;

public record EvaluationRun(
        EvalDataset dataset,
        List<CaseEvaluation> evaluations,
        List<JudgeOutcome> judgeOutcomes,
        EvaluationMetrics metrics,
        GateResult gates) {
    public EvaluationRun {
        evaluations = List.copyOf(evaluations == null ? List.of() : evaluations);
        judgeOutcomes = List.copyOf(judgeOutcomes == null ? List.of() : judgeOutcomes);
    }
}
