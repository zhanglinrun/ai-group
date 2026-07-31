package com.linrun.agent.eval.evaluator;

import com.linrun.agent.eval.dataset.EvalCase;
import com.linrun.agent.eval.runner.EvalRunObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed contract evaluator; absence of an exception is never a pass. */
public final class DeterministicEvaluator {
    private static final Set<String> FORBIDDEN_TRACE_KEY_PARTS = Set.of(
            "prompt", "argument", "secret", "file", "reasoning", "completion", "content");

    public CaseEvaluation evaluate(EvalCase evalCase, int trial, EvalRunObservation observation) {
        List<String> failures = new ArrayList<>();
        if (!observation.completed()) {
            failures.add("protocol: missing canonical complete event" + suffix(observation.failure()));
        }
        if (observation.requestId().isBlank() || observation.runId().isBlank()) {
            failures.add("schema: missing requestId or runId");
        }
        if (observation.answer().isBlank()) {
            failures.add("schema: missing final answer");
        }
        for (String claim : evalCase.expectedClaims()) {
            if (!contains(observation.answer(), claim)) {
                failures.add("claim: missing expected claim " + compact(claim));
            }
        }
        for (String tool : evalCase.requiredTools()) {
            if (!observation.successfulTools().stream().anyMatch(actual -> actual.equalsIgnoreCase(tool))) {
                failures.add("tool: required successful tool missing " + tool);
            }
        }
        for (var expectedTool : evalCase.expectedToolParameters().entrySet()) {
            var actualTool = observation.toolParameters().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(expectedTool.getKey()))
                    .findFirst();
            if (actualTool.isEmpty()) {
                failures.add("tool_parameter: no observed parameters for " + expectedTool.getKey());
                continue;
            }
            for (var expectedParameter : expectedTool.getValue().entrySet()) {
                String actualValue = actualTool.get().getValue().entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(expectedParameter.getKey()))
                        .map(java.util.Map.Entry::getValue).findFirst().orElse("");
                if (!actualValue.equals(expectedParameter.getValue())) {
                    failures.add("tool_parameter: incorrect " + expectedTool.getKey() + "."
                            + expectedParameter.getKey());
                }
            }
        }
        if (observation.successfulTools().size() < evalCase.minToolCalls()) {
            failures.add("tool: required minimum call count not reached");
        }
        for (String expectedCitation : evalCase.expectedCitations()) {
            if (!observation.citations().stream().anyMatch(actual -> actual.startsWith(expectedCitation))) {
                failures.add("citation: expected URL missing " + compact(expectedCitation));
            }
        }
        for (String forbidden : evalCase.forbiddenBehavior()) {
            if (contains(observation.answer(), forbidden)
                    || observation.successfulTools().stream().anyMatch(tool -> contains(tool, forbidden))) {
                failures.add("permission: forbidden behavior observed " + compact(forbidden));
            }
        }
        if (evalCase.requireFreshEvidence() && !observation.evidenceFresh()) {
            failures.add("citation: evidence freshness not confirmed");
        }
        if (evalCase.requireConflictPreservation() && !observation.conflictPreserved()) {
            failures.add("citation: conflict preservation not confirmed");
        }
        if (evalCase.requireRecoveryResume() && !observation.recoveryResumed()) {
            failures.add("recovery: resume not confirmed");
        }
        if (evalCase.requireQuotaSettlement() && observation.quotaSettlementCount() != 1) {
            failures.add("quota: expected exactly one settlement");
        }
        if (evalCase.requireNoHiddenChainOfThought() && (observation.hiddenThoughtExposed()
                || containsHiddenReasoning(observation.answer()))) {
            failures.add("privacy: hidden chain-of-thought exposed");
        }
        if (evalCase.maxCostMicrocredits() > 0 && observation.costMicrocredits() > evalCase.maxCostMicrocredits()) {
            failures.add("quota: max cost exceeded");
        }
        if (evalCase.maxLatencyMillis() > 0 && observation.latencyMillis() > evalCase.maxLatencyMillis()) {
            failures.add("latency: max latency exceeded");
        }
        for (String key : observation.traceAttributeNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_TRACE_KEY_PARTS.stream().anyMatch(lower::contains)) {
                failures.add("trace: forbidden attribute key " + key);
            }
        }
        return new CaseEvaluation(evalCase.id(), trial, failures.isEmpty(), failures, observation);
    }

    private static boolean contains(String actual, String expected) {
        return actual != null && expected != null && actual.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }

    private static String compact(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    private static String suffix(String value) {
        return value == null || value.isBlank() ? "" : ": " + compact(value);
    }

    private static boolean containsHiddenReasoning(String answer) {
        String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return normalized.contains("<think>") || normalized.contains("</think>")
                || normalized.contains("chain of thought") || normalized.contains("隐藏推理");
    }
}
