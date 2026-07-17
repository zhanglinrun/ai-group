package com.linrun.agent.domain.agent.runtime.tool.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Run-local ledger of successful canonical tool operations.
 *
 * <p>It keeps only one reference to each successful outcome and is cleared at
 * the start of every {@code BaseAgent.run}. Failures are deliberately not
 * stored, so the model can retry them. The run tool-call budget still counts
 * model-requested calls, including calls whose successful result is reused.</p>
 */
final class ToolOperationLedger {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private final ConcurrentMap<String, ToolExecutionOutcome> successfulOutcomes =
            new ConcurrentHashMap<>();

    void reset() {
        successfulOutcomes.clear();
    }

    ToolExecutionOutcome reuseSuccessful(ToolCall command, boolean allowRepeatedSuccessfulCall) {
        if (allowRepeatedSuccessfulCall) {
            return null;
        }
        ToolExecutionOutcome previous = successfulOutcomes.get(operationKey(command));
        return previous == null ? null : ToolExecutionOutcome.reusedFrom(previous);
    }

    void recordSuccessful(ToolCall command, ToolExecutionOutcome outcome) {
        if (command == null || outcome == null || !outcome.isSuccess() || outcome.isReused()) {
            return;
        }
        successfulOutcomes.putIfAbsent(operationKey(command), outcome);
    }

    String operationKey(ToolCall command) {
        String toolName = command == null || command.getFunction() == null
                ? ""
                : StringUtils.defaultString(command.getFunction().getName())
                .trim()
                .toLowerCase(Locale.ROOT);
        String arguments = command == null || command.getFunction() == null
                ? "{}"
                : canonicalArguments(command.getFunction().getArguments());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((toolName + "\n" + arguments).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String canonicalArguments(String arguments) {
        String normalized = StringUtils.isBlank(arguments) ? "{}" : arguments.trim();
        try {
            Object parsed = CANONICAL_MAPPER.readValue(normalized, Object.class);
            if (parsed instanceof String nestedJson && looksLikeJsonContainer(nestedJson)) {
                parsed = CANONICAL_MAPPER.readValue(nestedJson.trim(), Object.class);
            }
            return CANONICAL_MAPPER.writeValueAsString(sortJsonValue(parsed));
        } catch (JsonProcessingException ignored) {
            return normalized;
        }
    }

    private Object sortJsonValue(Object value) {
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

    private boolean looksLikeJsonContainer(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        return trimmed.startsWith("{") && trimmed.endsWith("}")
                || trimmed.startsWith("[") && trimmed.endsWith("]");
    }
}
