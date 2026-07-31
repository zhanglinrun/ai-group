package com.linrun.agent.eval.runner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Raw, redacted runtime facts retained by an evaluation trial. */
public record EvalRunObservation(
        String requestId,
        String runId,
        String traceId,
        String answer,
        List<String> successfulTools,
        Map<String, Map<String, String>> toolParameters,
        List<String> citations,
        Set<String> traceAttributeNames,
        boolean completed,
        boolean evidenceFresh,
        boolean conflictPreserved,
        boolean recoveryResumed,
        int quotaSettlementCount,
        long latencyMillis,
        long costMicrocredits,
        boolean hiddenThoughtExposed,
        String failure) {
    public EvalRunObservation {
        requestId = safe(requestId);
        runId = safe(runId);
        traceId = safe(traceId);
        answer = safe(answer);
        successfulTools = List.copyOf(successfulTools == null ? List.of() : successfulTools);
        toolParameters = copyParameters(toolParameters);
        citations = List.copyOf(citations == null ? List.of() : citations);
        traceAttributeNames = Set.copyOf(traceAttributeNames == null ? Set.of() : traceAttributeNames);
        failure = safe(failure);
        latencyMillis = Math.max(0L, latencyMillis);
        costMicrocredits = Math.max(0L, costMicrocredits);
        quotaSettlementCount = Math.max(0, quotaSettlementCount);
    }

    public EvalRunObservation(String requestId, String runId, String traceId, String answer,
                              List<String> successfulTools, List<String> citations,
                              Set<String> traceAttributeNames, boolean completed, boolean evidenceFresh,
                              boolean conflictPreserved, boolean recoveryResumed, int quotaSettlementCount,
                              long latencyMillis, long costMicrocredits, String failure) {
        this(requestId, runId, traceId, answer, successfulTools, Map.of(), citations, traceAttributeNames,
                completed, evidenceFresh, conflictPreserved, recoveryResumed, quotaSettlementCount,
                latencyMillis, costMicrocredits, false, failure);
    }

    public EvalRunObservation withTraceId(String resolvedTraceId) {
        if (resolvedTraceId == null || resolvedTraceId.isBlank() || resolvedTraceId.equals(traceId)) {
            return this;
        }
        return new EvalRunObservation(requestId, runId, resolvedTraceId, answer, successfulTools, toolParameters,
                citations, traceAttributeNames, completed, evidenceFresh, conflictPreserved, recoveryResumed,
                quotaSettlementCount, latencyMillis, costMicrocredits, hiddenThoughtExposed, failure);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Map<String, String>> copyParameters(Map<String, Map<String, String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Map<String, String>> result = new java.util.LinkedHashMap<>();
        source.forEach((tool, parameters) -> {
            if (tool != null && parameters != null) {
                result.put(tool, Map.copyOf(parameters));
            }
        });
        return Map.copyOf(result);
    }
}
