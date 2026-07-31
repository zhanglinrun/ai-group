package com.linrun.agent.domain.agent.runtime.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mechanical stop policy for one Agent Loop run. Semantic completion remains
 * the responsibility of CompletionGate.
 */
public final class StopGate {

    private static final String THINKING_COMPLETE_MARKER = "Thinking complete - no action needed";
    private static final ObjectMapper SIGNATURE_MAPPER = new ObjectMapper();

    private int duplicateThreshold = 2;
    private String lastStepSignature;
    private int repeatedStepCount;
    private long runStartedAtMillis;

    public void beginRun(AgentContext context, AgentRunBudget budget) {
        lastStepSignature = null;
        repeatedStepCount = 0;
        runStartedAtMillis = System.currentTimeMillis();
        if (context != null && !context.hasRunDeadline()) {
            context.activateRunDeadline(effectiveBudget(budget).maxDurationMillis());
        }
    }

    public AgentStopReason beforeTurn(AgentContext context, AgentRunBudget budget) {
        AgentRunBudget effective = effectiveBudget(budget);
        if (context != null) {
            AgentStopReason cancellation = context.cancellationReason();
            if (cancellation != AgentStopReason.NONE) {
                return cancellation;
            }
        } else if (remainingDuration(null, effective).isZero()) {
            return AgentStopReason.TIME_BUDGET;
        }
        if (context == null || context.getAgentRunState() == null) {
            return AgentStopReason.NONE;
        }
        if (context.getAgentRunState().getTotalTokenCountValue() >= effective.maxTotalTokens()) {
            return AgentStopReason.TOKEN_BUDGET;
        }
        if (context.getAgentRunState().getChargedMicrocreditsValue() >= effective.maxMicrocredits()) {
            return AgentStopReason.CREDIT_BUDGET;
        }
        return AgentStopReason.NONE;
    }

    /** Re-check hard budgets immediately after a settled model invocation. */
    public AgentStopReason afterModelCall(AgentContext context, AgentRunBudget budget) {
        return beforeTurn(context, budget);
    }

    /**
     * Compare an opaque semantic turn signature. Callers must not pass a volatile
     * tool observation here; tool turns are identified by their requested action.
     */
    public boolean isRepeatedTurn(String turnSignature) {
        if (duplicateThreshold <= 0) {
            return false;
        }
        String signature = turnSignature == null ? "" : turnSignature.trim();
        if (signature.isEmpty()) {
            lastStepSignature = null;
            repeatedStepCount = 0;
            return false;
        }
        if (signature.equals(lastStepSignature)) {
            repeatedStepCount++;
        } else {
            lastStepSignature = signature;
            repeatedStepCount = 0;
        }
        return repeatedStepCount >= duplicateThreshold;
    }

    /** Stable fallback for a no-tool candidate answer or a generic BaseAgent step. */
    public static String contentSignature(String content) {
        String normalized = content == null
                ? ""
                : content.trim().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty() || THINKING_COMPLETE_MARKER.equals(normalized)) {
            return null;
        }
        return "content:" + sha256(normalized);
    }

    /**
     * Stable signature for one model-selected tool batch. Tool call ids and tool
     * results are intentionally excluded because both can change across retries.
     */
    public static String toolCallsSignature(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        for (ToolCall toolCall : toolCalls) {
            ToolCall.Function function = toolCall == null ? null : toolCall.getFunction();
            String toolName = function == null || function.getName() == null
                    ? ""
                    : function.getName().trim().toLowerCase(Locale.ROOT);
            String arguments = canonicalArguments(function == null ? null : function.getArguments());
            canonical.append(toolName.length()).append(':').append(toolName)
                    .append('|').append(arguments.length()).append(':').append(arguments)
                    .append('\n');
        }
        return "tools:" + sha256(canonical.toString());
    }

    private static String canonicalArguments(String arguments) {
        String normalized = arguments == null || arguments.isBlank() ? "{}" : arguments.trim();
        try {
            Object parsed = SIGNATURE_MAPPER.readValue(normalized, Object.class);
            if (parsed instanceof String nestedJson && looksLikeJsonContainer(nestedJson)) {
                parsed = SIGNATURE_MAPPER.readValue(nestedJson.trim(), Object.class);
            }
            return SIGNATURE_MAPPER.writeValueAsString(sortJsonValue(parsed));
        } catch (JsonProcessingException ignored) {
            return normalized;
        }
    }

    private static Object sortJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), sortJsonValue(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sortedChildren = new ArrayList<>(list.size());
            list.forEach(child -> sortedChildren.add(sortJsonValue(child)));
            return sortedChildren;
        }
        return value;
    }

    private static boolean looksLikeJsonContainer(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public Duration remainingDuration(AgentContext context, AgentRunBudget budget) {
        AgentRunBudget effective = effectiveBudget(budget);
        if (context != null && context.hasRunDeadline()) {
            return context.remainingRunDuration();
        }
        if (runStartedAtMillis <= 0L) {
            return Duration.ofMillis(effective.maxDurationMillis());
        }
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - runStartedAtMillis);
        return Duration.ofMillis(Math.max(0L, effective.maxDurationMillis() - elapsedMillis));
    }

    public int getDuplicateThreshold() {
        return duplicateThreshold;
    }

    public void setDuplicateThreshold(int duplicateThreshold) {
        this.duplicateThreshold = duplicateThreshold;
    }

    private AgentRunBudget effectiveBudget(AgentRunBudget budget) {
        return budget == null ? AgentRunBudget.defaults() : budget;
    }
}
