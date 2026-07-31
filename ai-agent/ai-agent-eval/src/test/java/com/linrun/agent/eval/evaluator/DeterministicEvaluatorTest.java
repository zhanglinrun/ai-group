package com.linrun.agent.eval.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.eval.dataset.EvalCase;
import com.linrun.agent.eval.runner.EvalRunObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsParameterBoundaryQuotaAndHiddenReasoningViolations() throws Exception {
        EvalCase evalCase = EvalCase.from(json.readTree("""
                {"id":"contract","input":"x","mode":"STANDARD","requiredTools":["analyze_file"],
                 "expectedToolParameters":{"analyze_file":{"artifactId":"fixture:good"}},
                 "expectedClaims":["safe"],"forbiddenBehavior":["evil"],"requireQuotaSettlement":true}
                """));
        EvalRunObservation observation = new EvalRunObservation("request", "run", "trace",
                "safe <think>private</think> evil", List.of("analyze_file"),
                Map.of("analyze_file", Map.of("artifactId", "fixture:wrong")), List.of(), Set.of("safe.attribute"),
                true, true, true, true, 2, 1L, 0L, true, "");

        CaseEvaluation evaluation = new DeterministicEvaluator().evaluate(evalCase, 1, observation);

        assertFalse(evaluation.passed());
        assertTrue(evaluation.failures().stream().anyMatch(value -> value.startsWith("tool_parameter:")));
        assertTrue(evaluation.failures().stream().anyMatch(value -> value.startsWith("permission:")));
        assertTrue(evaluation.failures().stream().anyMatch(value -> value.startsWith("quota:")));
        assertTrue(evaluation.failures().stream().anyMatch(value -> value.startsWith("privacy:")));
    }

    @Test
    void rejectsSensitiveTraceAttributeNames() throws Exception {
        EvalCase evalCase = EvalCase.from(json.readTree("{\"id\":\"trace\",\"input\":\"x\",\"mode\":\"STANDARD\"}"));
        EvalRunObservation observation = new EvalRunObservation("request", "run", "trace", "ok", List.of(), List.of(),
                Set.of("gen_ai.prompt"), true, true, true, true, 0, 1L, 0L, "");

        CaseEvaluation evaluation = new DeterministicEvaluator().evaluate(evalCase, 1, observation);

        assertFalse(evaluation.passed());
        assertTrue(evaluation.failures().stream().anyMatch(value -> value.startsWith("trace:")));
    }
}
