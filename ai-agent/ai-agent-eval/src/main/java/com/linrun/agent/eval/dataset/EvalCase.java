package com.linrun.agent.eval.dataset;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A normalized, hashable evaluation case. Legacy docs/evals JSONL fields are accepted on read. */
public record EvalCase(
        String id,
        String input,
        String mode,
        boolean online,
        String attachmentFixture,
        List<String> requiredTools,
        List<String> expectedClaims,
        List<String> expectedCitations,
        Map<String, Map<String, String>> expectedToolParameters,
        List<String> forbiddenBehavior,
        int minToolCalls,
        long maxCostMicrocredits,
        long maxLatencyMillis,
        boolean requireFreshEvidence,
        boolean requireConflictPreservation,
        boolean requireRecoveryResume,
        boolean requireQuotaSettlement,
        boolean requireNoHiddenChainOfThought,
        JsonNode offlineFixture) {

    public EvalCase {
        id = required(id, "case id");
        input = required(input, "case input");
        mode = required(mode, "case mode").toUpperCase(Locale.ROOT);
        attachmentFixture = attachmentFixture == null ? "" : attachmentFixture;
        requiredTools = immutable(requiredTools);
        expectedClaims = immutable(expectedClaims);
        expectedCitations = immutable(expectedCitations);
        expectedToolParameters = immutableParameters(expectedToolParameters);
        forbiddenBehavior = immutable(forbiddenBehavior);
        minToolCalls = Math.max(0, minToolCalls);
        maxCostMicrocredits = Math.max(0L, maxCostMicrocredits);
        maxLatencyMillis = Math.max(0L, maxLatencyMillis);
    }

    public static EvalCase from(JsonNode source) {
        Objects.requireNonNull(source, "case JSON must not be null");
        String id = text(source, "id", "caseId");
        String input = text(source, "input", "query");
        String mode = text(source, "mode", "executionMode");
        if (mode.isBlank()) {
            mode = "STANDARD";
        }
        LinkedHashSet<String> requiredTools = new LinkedHashSet<>();
        requiredTools.addAll(texts(source, "requiredTools"));
        requiredTools.addAll(texts(source, "expectSuccessfulToolsAll"));
        requiredTools.addAll(texts(source, "expectTools"));
        LinkedHashSet<String> expectedClaims = new LinkedHashSet<>();
        expectedClaims.addAll(texts(source, "expectedClaims"));
        expectedClaims.addAll(texts(source, "expectAll"));
        expectedClaims.addAll(texts(source, "expect"));
        return new EvalCase(
                id, input, mode, source.path("online").asBoolean(false),
                text(source, "attachmentFixture", "attachments"),
                new ArrayList<>(requiredTools), new ArrayList<>(expectedClaims),
                texts(source, "expectedCitations", "expectedCitationUrls"),
                toolParameters(source.path("expectedToolParameters")),
                texts(source, "forbiddenBehavior", "forbidden"),
                source.path("minToolCalls").asInt(0),
                source.path("maxCostMicrocredits").asLong(0L),
                source.path("maxLatencyMillis").asLong(source.path("timeoutSec").asLong(0L) * 1_000L),
                source.path("requireFreshEvidence").asBoolean(false),
                source.path("requireConflictPreservation").asBoolean(false),
                source.path("requireRecoveryResume").asBoolean(false),
                source.path("requireQuotaSettlement").asBoolean(false),
                source.path("requireNoHiddenChainOfThought").asBoolean(true),
                source.path("offlineFixture"));
    }

    private static String text(JsonNode source, String... names) {
        for (String name : names) {
            JsonNode value = source.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static List<String> texts(JsonNode source, String... names) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String name : names) {
            JsonNode node = source.path(name);
            if (node.isTextual() && !node.asText().isBlank()) {
                values.add(node.asText().trim());
            }
            if (node.isArray()) {
                node.forEach(value -> {
                    if (value.isTextual() && !value.asText().isBlank()) {
                        values.add(value.asText().trim());
                    }
                });
            }
        }
        return new ArrayList<>(values);
    }

    private static Map<String, Map<String, String>> toolParameters(JsonNode source) {
        if (!source.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isObject()) {
                return;
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            entry.getValue().fields().forEachRemaining(parameter -> {
                if (parameter.getValue().isValueNode()) {
                    parameters.put(parameter.getKey(), parameter.getValue().asText());
                }
            });
            result.put(entry.getKey(), parameters);
        });
        return result;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static List<String> immutable(List<String> values) {
        return List.copyOf(values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList());
    }

    private static Map<String, Map<String, String>> immutableParameters(Map<String, Map<String, String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        source.forEach((tool, parameters) -> {
            if (tool == null || tool.isBlank() || parameters == null) {
                return;
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            parameters.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) {
                    normalized.put(name.trim(), value.trim());
                }
            });
            result.put(tool.trim(), Map.copyOf(normalized));
        });
        return Map.copyOf(result);
    }
}
