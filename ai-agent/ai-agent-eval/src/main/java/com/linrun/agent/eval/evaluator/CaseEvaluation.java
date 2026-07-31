package com.linrun.agent.eval.evaluator;

import com.linrun.agent.eval.runner.EvalRunObservation;

import java.util.List;

public record CaseEvaluation(
        String caseId,
        int trial,
        boolean passed,
        List<String> failures,
        EvalRunObservation observation) {
    public CaseEvaluation {
        failures = List.copyOf(failures == null ? List.of() : failures);
    }
}
