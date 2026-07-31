package com.linrun.agent.domain.agent.runtime.observability;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds the small, allowlisted attribute projection shared by Ledger-linked
 * traces. Prompt bodies, tool arguments, file content and hidden reasoning are
 * intentionally not representable by this mapper.
 */
public final class AgentTraceMapper {

    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String RUN_ID = "aigroup.agent.run_id";
    public static final String STEP_TYPE = "aigroup.agent.step_type";
    public static final String TOOL_RISK = "aigroup.agent.tool_risk";
    public static final String CONTEXT_REVISION = "aigroup.agent.context_revision";
    public static final String EVIDENCE_COUNT = "aigroup.agent.evidence_count";
    public static final String TOOL_NAME = "aigroup.agent.tool_name";
    public static final String TOOL_PROVIDER = "aigroup.agent.tool_provider";
    public static final String STATUS = "aigroup.agent.status";
    public static final String LEDGER_REQUEST_ID = "aigroup.ledger.request_id";
    public static final String LEDGER_RUN_ID = "aigroup.ledger.run_id";
    public static final String LEDGER_LLM_INVOCATION_ID = "aigroup.ledger.llm_invocation_id";
    public static final String LEDGER_TOOL_INVOCATION_ID = "aigroup.ledger.tool_invocation_id";
    public static final String LEDGER_TOOL_CALL_ID = "aigroup.ledger.tool_call_id";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            GEN_AI_SYSTEM, GEN_AI_REQUEST_MODEL, GEN_AI_USAGE_INPUT_TOKENS,
            GEN_AI_USAGE_OUTPUT_TOKENS, GEN_AI_OPERATION_NAME, RUN_ID, STEP_TYPE,
            TOOL_RISK, CONTEXT_REVISION, EVIDENCE_COUNT, TOOL_NAME, TOOL_PROVIDER,
            STATUS, LEDGER_REQUEST_ID, LEDGER_RUN_ID, LEDGER_LLM_INVOCATION_ID,
            LEDGER_TOOL_INVOCATION_ID, LEDGER_TOOL_CALL_ID);

    public Map<String, String> session(AgentContext context) {
        return common(context, "session");
    }

    public Map<String, String> run(AgentContext context) {
        return common(context, "run");
    }

    public Map<String, String> graph(AgentContext context) {
        return common(context, "graph");
    }

    public Map<String, String> contextCompaction(AgentContext context) {
        return common(context, "context.compaction");
    }

    public Map<String, String> model(AgentContext context, String model, Long invocationId) {
        Map<String, Object> attributes = new LinkedHashMap<>(common(context, "chat"));
        attributes.put(GEN_AI_SYSTEM, "spring_ai_alibaba");
        attributes.put(GEN_AI_REQUEST_MODEL, model);
        attributes.put(LEDGER_LLM_INVOCATION_ID, invocationId);
        return sanitize(attributes);
    }

    public Map<String, String> modelCompletion(Integer inputTokens,
                                                 Integer outputTokens,
                                                 String status) {
        return sanitize(Map.of(
                GEN_AI_USAGE_INPUT_TOKENS, nonNegative(inputTokens),
                GEN_AI_USAGE_OUTPUT_TOKENS, nonNegative(outputTokens),
                STATUS, status));
    }

    public Map<String, String> tool(AgentContext context,
                                    String toolName,
                                    String toolProvider,
                                    Long toolInvocationId,
                                    String toolCallId) {
        Map<String, Object> attributes = new LinkedHashMap<>(common(context, "tool"));
        attributes.put(TOOL_NAME, toolName);
        attributes.put(TOOL_PROVIDER, toolProvider);
        attributes.put(TOOL_RISK, toolRisk(toolName));
        attributes.put(LEDGER_TOOL_INVOCATION_ID, toolInvocationId);
        attributes.put(LEDGER_TOOL_CALL_ID, toolCallId);
        return sanitize(attributes);
    }

    public Map<String, String> quota(AgentContext context, String operation, Long invocationId) {
        Map<String, Object> attributes = new LinkedHashMap<>(common(context, "quota." + operation));
        attributes.put(LEDGER_LLM_INVOCATION_ID, invocationId);
        return sanitize(attributes);
    }

    /** Returns only declared safe keys and token-shaped values. */
    public Map<String, String> sanitize(Map<String, ?> candidates) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty()) {
            return safe;
        }
        candidates.forEach((key, value) -> {
            if (!ALLOWED_KEYS.contains(key) || value == null) {
                return;
            }
            String normalized = normalize(key, value);
            if (normalized != null) {
                safe.put(key, normalized);
            }
        });
        return Map.copyOf(safe);
    }

    private Map<String, String> common(AgentContext context, String operation) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(GEN_AI_OPERATION_NAME, operation);
        if (context == null || context.getAgentRunState() == null) {
            return sanitize(attributes);
        }
        var state = context.getAgentRunState();
        attributes.put(RUN_ID, state.getRunId());
        attributes.put(STEP_TYPE, context.getExecutionProfile());
        attributes.put(CONTEXT_REVISION, state.getContextRevisionValue());
        attributes.put(EVIDENCE_COUNT, state.getEvidenceCountValue());
        attributes.put(LEDGER_RUN_ID, state.getRunId());
        attributes.put(LEDGER_REQUEST_ID, context.getRequestId());
        return sanitize(attributes);
    }

    private String toolRisk(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = toolName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("delete") || normalized.contains("payment") || normalized.contains("transfer")) {
            return "HIGH";
        }
        if (normalized.contains("write") || normalized.contains("upload") || normalized.contains("mcp")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String normalize(String key, Object value) {
        if (value instanceof Number number) {
            return String.valueOf(Math.max(0L, number.longValue()));
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || text.length() > 128
                || text.matches(".*(?i)(api[_-]?key|authorization|bearer|password|secret|token=).*")) {
            return null;
        }
        if (GEN_AI_OPERATION_NAME.equals(key) || STEP_TYPE.equals(key) || STATUS.equals(key)
                || TOOL_RISK.equals(key) || TOOL_NAME.equals(key) || TOOL_PROVIDER.equals(key)
                || GEN_AI_SYSTEM.equals(key) || GEN_AI_REQUEST_MODEL.equals(key)
                || LEDGER_REQUEST_ID.equals(key) || LEDGER_TOOL_CALL_ID.equals(key)) {
            return text.matches("[A-Za-z0-9._:/-]+") ? text : null;
        }
        return text.matches("[0-9]+") ? text : null;
    }

    private long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }
}
