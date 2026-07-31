package com.linrun.agent.eval.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.linrun.agent.eval.dataset.EvalCase;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic runner for PR contracts and fixture-only reproduction. */
public final class OfflineFixtureRunner implements EvalCaseRunner {
    @Override
    public EvalRunObservation run(EvalCase evalCase, int trial) {
        JsonNode fixture = evalCase.offlineFixture();
        String answer = fixture.path("answer").asText(String.join(" ", evalCase.expectedClaims()));
        List<String> tools = textList(fixture.path("successfulTools"), evalCase.requiredTools());
        List<String> citations = textList(fixture.path("citations"), evalCase.expectedCitations());
        Map<String, Map<String, String>> toolParameters = parameters(fixture.path("toolParameters"));
        Set<String> attributes = new LinkedHashSet<>(textList(fixture.path("traceAttributeNames"), List.of(
                "aigroup.ledger.request_id", "aigroup.ledger.run_id", "gen_ai.operation.name")));
        return new EvalRunObservation(
                "offline:" + evalCase.id() + ":" + trial,
                "offline-run:" + evalCase.id() + ":" + trial,
                "offline-trace:" + evalCase.id() + ":" + trial,
                answer, tools, toolParameters, citations, attributes,
                fixture.path("completed").asBoolean(true),
                fixture.path("evidenceFresh").asBoolean(!evalCase.requireFreshEvidence() || true),
                fixture.path("conflictPreserved").asBoolean(!evalCase.requireConflictPreservation() || true),
                fixture.path("recoveryResumed").asBoolean(!evalCase.requireRecoveryResume() || true),
                fixture.path("quotaSettlementCount").asInt(evalCase.requireQuotaSettlement() ? 1 : 0),
                fixture.path("latencyMillis").asLong(15L),
                fixture.path("costMicrocredits").asLong(0L),
                fixture.path("hiddenThoughtExposed").asBoolean(false),
                fixture.path("failure").asText(""));
    }

    private static List<String> textList(JsonNode node, List<String> fallback) {
        if (!node.isArray()) {
            return fallback;
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual).map(JsonNode::asText).toList();
    }

    private static Map<String, Map<String, String>> parameters(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(tool -> {
            if (!tool.getValue().isObject()) {
                return;
            }
            Map<String, String> values = new LinkedHashMap<>();
            tool.getValue().fields().forEachRemaining(value -> {
                if (value.getValue().isValueNode()) {
                    values.put(value.getKey(), value.getValue().asText());
                }
            });
            result.put(tool.getKey(), values);
        });
        return result;
    }
}
