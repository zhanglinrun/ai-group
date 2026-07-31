package com.linrun.agent.domain.agent.runtime.tool.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.harness.ToolPermissionMetadata;
import com.linrun.agent.domain.agent.runtime.harness.ToolRiskLevel;
import com.linrun.agent.domain.agent.runtime.harness.ToolSideEffect;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Run-local metadata registry. It is deliberately derived from the already
 * authorized ToolCollection: a descriptor cannot grant access to a tool that
 * the active turn did not expose.
 */
public final class ToolRegistry {

    private static final Set<String> INTERNAL_NAMES = Set.of(
            "tool_search", "execute_extra_tool", "todo_write");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Map<String, ToolDescriptor> descriptors;

    private ToolRegistry(Map<String, ToolDescriptor> descriptors) {
        this.descriptors = Map.copyOf(descriptors);
    }

    public static ToolRegistry from(ToolCollection collection) {
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();
        if (collection == null) {
            return new ToolRegistry(descriptors);
        }
        collection.getToolMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> descriptors.put(entry.getKey(), fromCore(entry.getValue())));
        collection.getMcpToolMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> descriptors.put(entry.getKey(), fromMcp(entry.getValue())));
        return new ToolRegistry(descriptors);
    }

    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    public List<ToolDescriptor> listEnabled() {
        return descriptors.values().stream()
                .filter(ToolDescriptor::enabled)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public Map<String, ToolDescriptor> snapshot() {
        return descriptors;
    }

    private static ToolDescriptor fromCore(BaseTool tool) {
        String name = tool.getName();
        String inputSchema = canonicalJson(tool.toParams());
        ToolPermissionMetadata permission = tool.permissionMetadata();
        String definitionHash = hash(String.join("\n", name, inputSchema,
                String.valueOf(permission.riskLevel()), String.valueOf(permission.sideEffect())));
        ToolSource source = INTERNAL_NAMES.contains(name) ? ToolSource.INTERNAL : ToolSource.CORE;
        return new ToolDescriptor(
                name,
                "core-" + definitionHash.substring("sha256:".length(), 20),
                definitionHash,
                inputSchema,
                genericOutputSchema(),
                permission.riskLevel(),
                permission.sideEffect(),
                120,
                tool.isRetryable() ? ToolRetryPolicy.TRANSIENT_ONLY : ToolRetryPolicy.NONE,
                safeConcurrency(tool),
                permission.requiresApproval(),
                "agent-runtime",
                true,
                source);
    }

    private static ToolDescriptor fromMcp(McpToolInfo tool) {
        ToolRiskLevel risk = tool.getRiskLevel();
        ToolSideEffect sideEffect = tool.getSideEffect();
        int timeout = tool.getDescriptor() == null || tool.getDescriptor().getRequestTimeout() == null
                ? 60
                : Math.max(1, tool.getDescriptor().getRequestTimeout());
        String definitionHash = tool.definitionHash();
        return new ToolDescriptor(
                tool.resolveExposedName(),
                "mcp-" + definitionHash.substring("sha256:".length(), 20),
                definitionHash,
                tool.getParameters(),
                tool.getOutputSchema(),
                risk,
                sideEffect,
                timeout,
                ToolRetryPolicy.NONE,
                false,
                new ToolPermissionMetadata(risk, sideEffect).requiresApproval(),
                "mcp:" + tool.getMcpId(),
                true,
                ToolSource.MCP);
    }

    private static boolean safeConcurrency(BaseTool tool) {
        try {
            return tool.isConcurrencySafe(Map.of());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String genericOutputSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":true}";
    }

    private static String canonicalJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
