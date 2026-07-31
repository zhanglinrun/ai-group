package com.linrun.agent.eval.report;

import com.linrun.agent.eval.evaluator.EvaluationRun;

/** Serialized root document for result.json. */
public record EvaluationResult(
        String generatedAtUtc,
        String runner,
        int trials,
        String datasetHash,
        String configHash,
        String gitCommit,
        boolean dirtyWorktree,
        EvaluationRun evaluation) {
}
