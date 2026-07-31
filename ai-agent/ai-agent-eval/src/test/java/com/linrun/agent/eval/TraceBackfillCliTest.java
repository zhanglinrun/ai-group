package com.linrun.agent.eval;

import com.linrun.agent.eval.dataset.DatasetCatalog;
import com.linrun.agent.eval.evaluator.CaseEvaluation;
import com.linrun.agent.eval.evaluator.EvaluationMetrics;
import com.linrun.agent.eval.evaluator.EvaluationRun;
import com.linrun.agent.eval.evaluator.GateResult;
import com.linrun.agent.eval.report.EvaluationResult;
import com.linrun.agent.eval.runner.EvalRunObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceBackfillCliTest {
    @Test
    void enrichesSavedObservationsWithoutChangingEvaluationOutcome() throws Exception {
        EvalRunObservation observation = new EvalRunObservation("request", "run", "", "answer", List.of(), List.of(),
                Set.of(), true, true, true, true, 0, 1L, 0L, "");
        CaseEvaluation evaluation = new CaseEvaluation("standard-arithmetic", 1, true, List.of(), observation);
        EvaluationRun run = new EvaluationRun(DatasetCatalog.loadDefault(), List.of(evaluation), List.of(),
                new EvaluationMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()), new GateResult(true, List.of()));
        EvaluationResult source = new EvaluationResult("now", "GATEWAY", 1, run.dataset().sha256(), "sha256:config",
                "commit", true, run);

        EvaluationResult result = TraceBackfillCli.backfill(source, ignored -> "trace-123");

        assertEquals("trace-123", result.evaluation().evaluations().getFirst().observation().traceId());
        assertEquals(true, result.evaluation().gates().passed());
    }
}
