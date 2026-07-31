package com.linrun.agent.domain.agent.runtime.llm;

import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.types.common.JsonUtils;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds reproducible, secret-free model invocation snapshots. */
public final class InvocationSnapshotFactory {

    private InvocationSnapshotFactory() {
    }

    public static InvocationSnapshot forAgentCall(Object promptInput,
                                                   LLMSettings settings,
                                                   ToolCollection tools) {
        LLMSettings effective = settings == null ? new LLMSettings() : settings;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("model", effective.getModel());
        parameters.put("maxTokens", effective.getMaxTokens());
        parameters.put("maxInputTokens", effective.getMaxInputTokens());
        parameters.put("temperature", effective.getTemperature());
        parameters.put("apiType", effective.getApiType());
        parameters.put("apiVersion", effective.getApiVersion());
        parameters.put("functionCallType", effective.getFunctionCallType());
        parameters.put("inputCreditsPerMillion", effective.getInputCreditsPerMillion());
        parameters.put("outputCreditsPerMillion", effective.getOutputCreditsPerMillion());
        parameters.put("extParamsHash", sha256(canonicalJson(effective.getExtParams() == null
                ? Map.of()
                : new TreeMap<>(effective.getExtParams()))));
        return build(promptInput, parameters, tools);
    }

    public static InvocationSnapshot forDirectCall(Prompt prompt, ModelInvocationPolicy policy) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("model", policy.modelName());
        parameters.put("maxTokens", policy.maxOutputTokens());
        parameters.put("temperature", policy.temperature());
        parameters.put("inputCreditsPerMillion", policy.inputRateSnapshot());
        parameters.put("outputCreditsPerMillion", policy.outputRateSnapshot());
        return build(prompt, parameters, null);
    }

    private static InvocationSnapshot build(Object promptInput,
                                            Map<String, Object> modelParameters,
                                            ToolCollection tools) {
        String parametersJson = JsonUtils.toJson(modelParameters);
        String toolSnapshotJson = JsonUtils.toJson(toolSnapshot(tools));
        // Active skills do not have a runtime collection yet. Record the explicit
        // empty snapshot instead of inventing a version from configuration.
        String skillSnapshotJson = "[]";
        return new InvocationSnapshot(
                sha256(canonicalJson(promptInput)),
                parametersJson,
                toolSnapshotJson,
                skillSnapshotJson,
                sha256(parametersJson + "|" + toolSnapshotJson + "|" + skillSnapshotJson));
    }

    private static List<Map<String, Object>> toolSnapshot(ToolCollection tools) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        if (tools == null) {
            return snapshot;
        }
        for (Map.Entry<String, BaseTool> entry : new TreeMap<>(tools.getToolMap()).entrySet()) {
            BaseTool tool = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "LOCAL");
            item.put("name", entry.getKey());
            item.put("implementation", tool == null ? null : tool.getClass().getName());
            item.put("schemaHash", tool == null ? null : sha256(canonicalJson(tool.toParams())));
            snapshot.add(item);
        }
        for (Map.Entry<String, McpToolInfo> entry : new TreeMap<>(tools.getMcpToolMap()).entrySet()) {
            McpToolInfo tool = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "MCP");
            item.put("name", entry.getKey());
            item.put("provider", tool == null ? null : tool.getServerKey());
            item.put("schemaHash", tool == null ? null : sha256(tool.getParameters()));
            snapshot.add(item);
        }
        return snapshot;
    }

    private static String canonicalJson(Object value) {
        String json = JsonUtils.toJson(value);
        return json == null ? String.valueOf(value) : json;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record InvocationSnapshot(String promptHash,
                                     String modelParametersJson,
                                     String toolSnapshotJson,
                                     String skillSnapshotJson,
                                     String configHash) {
    }
}
