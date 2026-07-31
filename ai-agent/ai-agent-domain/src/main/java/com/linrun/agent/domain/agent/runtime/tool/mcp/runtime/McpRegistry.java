package com.linrun.agent.domain.agent.runtime.tool.mcp.runtime;

import com.linrun.agent.types.common.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.valobj.AiClientToolMcpVO;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolInputSchemaValidator;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpEndpointPolicy;

import java.lang.reflect.Array;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 统一运行时注册中心。
 * 负责配置加载、客户端预热、工具缓存和统一执行入口。
 */
@Slf4j
@Service
public class McpRegistry {

    /**
     * MCP SDK 大量使用 Java record，使用 Jackson 序列化更稳定。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserMcpEndpointPolicy userMcpEndpointPolicy = new UserMcpEndpointPolicy();
    private final McpToolMetadataPolicy toolMetadataPolicy = new McpToolMetadataPolicy();

    @Resource
    private IAgentRepository repository;

    @Resource
    private McpClientRuntimeFactory runtimeFactory;

    /**
     * 运行时缓存：key 为 mcpId。
     */
    private final Map<String, McpClientRuntime> runtimeCache = new ConcurrentHashMap<>();

    /**
     * 工具发现结果缓存：key 为 mcpId。
     */
    private final Map<String, List<McpToolInfo>> toolCache = new ConcurrentHashMap<>();

    /**
     * fix 策略使用的 ToolCallback 缓存：key 为 mcpId。
     */
    private final Map<String, List<ToolCallback>> toolCallbackCache = new ConcurrentHashMap<>();

    /**
     * Last successfully initialized configured-MCP snapshot by id. A snapshot change is the
     * explicit invalidation signal; repeated armory assembly must not reconnect unchanged MCPs.
     */
    private final Map<String, String> loadedConfigFingerprints = new ConcurrentHashMap<>();

    /**
     * Negative cache for configured MCP startup failures. Broken trusted STDIO configuration must
     * become an unavailable tool, not add one request-timeout to every Agent branch.
     */
    private final Map<String, String> failedConfigFingerprints = new ConcurrentHashMap<>();

    /**
     * 客户端与 MCP 绑定关系缓存：key 为 clientId。
     */
    private final Map<String, List<String>> clientMcpIdCache = new ConcurrentHashMap<>();

    /**
     * 全局启用 MCP 的快照。
     */
    private volatile List<String> globalEnabledMcpIds = Collections.emptyList();

    /**
     * 预热全局启用的 MCP。
     */
    public synchronized void preloadAllEnabledMcps() {
        List<AiClientToolMcpVO> enabledMcpList = repository.queryEnabledAiClientToolMcpVOList();
        Map<String, AiClientToolMcpVO> mcpMap = enabledMcpList.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotBlank(item.getMcpId()))
                .collect(Collectors.toMap(
                        AiClientToolMcpVO::getMcpId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        syncDisabledMcps(mcpMap.keySet());
        preloadMcps(new ArrayList<>(mcpMap.values()));
        globalEnabledMcpIds = List.copyOf(mcpMap.keySet());

        log.info("MCP 全局预热完成，启用数量：{}", globalEnabledMcpIds.size());
    }

    /**
     * 预热指定客户端关联的 MCP 及其绑定关系。
     */
    public synchronized void preloadClientMcps(List<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return;
        }

        Map<String, List<String>> clientMcpIdMap = repository.queryEnabledClientMcpIdMap(clientIds);
        for (String clientId : clientIds) {
            List<String> mcpIds = clientMcpIdMap.getOrDefault(clientId, Collections.emptyList());
            clientMcpIdCache.put(clientId, List.copyOf(mcpIds));
        }

        List<AiClientToolMcpVO> clientMcpList = repository.AiClientToolMcpVOByClientIds(clientIds);
        preloadMcps(clientMcpList);

        log.info("MCP 客户端绑定预热完成，clientIds={}", clientIds);
    }

    /**
     * 获取全局启用 MCP 的工具列表。
     */
    public List<McpToolInfo> listGlobalEnabledTools() {
        ensureGlobalMcpsLoaded();
        return listToolsByMcpIds(globalEnabledMcpIds);
    }

    /** Returns only tools bound to the selected role/client profiles. */
    public synchronized List<McpToolInfo> listToolsByClientIds(List<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return List.of();
        }
        preloadClientMcps(clientIds);
        LinkedHashSet<String> mcpIds = new LinkedHashSet<>();
        for (String clientId : clientIds) {
            mcpIds.addAll(clientMcpIdCache.getOrDefault(clientId, List.of()));
        }
        return listToolsByMcpIds(new ArrayList<>(mcpIds));
    }

    /**
     * Returns offline-eligible STDIO tools bound to the selected clients without
     * initializing any HTTP/SSE MCP runtime.
     */
    public synchronized List<McpToolInfo> listOfflineEligibleToolsByClientIds(List<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> clientMcpIdMap = repository.queryEnabledClientMcpIdMap(clientIds);
        LinkedHashSet<String> selectedMcpIds = new LinkedHashSet<>();
        for (String clientId : clientIds) {
            selectedMcpIds.addAll(clientMcpIdMap.getOrDefault(clientId, List.of()));
        }
        if (selectedMcpIds.isEmpty()) {
            return List.of();
        }

        List<AiClientToolMcpVO> stdioConfigs = repository.queryEnabledAiClientToolMcpVOList().stream()
                .filter(Objects::nonNull)
                .filter(item -> selectedMcpIds.contains(item.getMcpId()))
                .filter(item -> McpServerDescriptor.TRANSPORT_TYPE_STDIO.equals(item.getTransportType()))
                .toList();
        List<AiClientToolMcpVO> missingConfigs = stdioConfigs.stream()
                .filter(item -> !runtimeCache.containsKey(item.getMcpId())
                        || !toolCache.containsKey(item.getMcpId()))
                .toList();
        preloadMcps(missingConfigs);

        List<McpToolInfo> tools = new ArrayList<>();
        for (AiClientToolMcpVO config : stdioConfigs) {
            for (McpToolInfo toolInfo : toolCache.getOrDefault(config.getMcpId(), List.of())) {
                if (toolInfo != null && toolInfo.isOfflineEligible()) {
                    tools.add(toolInfo);
                }
            }
        }
        return List.copyOf(tools);
    }

    /**
     * 获取离线请求可用的系统 MCP 工具。
     *
     * <p>这里必须在创建运行时之前按配置过滤，只初始化管理员配置的 STDIO MCP。
     * 不能先调用 {@link #listGlobalEnabledTools()} 再过滤，否则一次离线请求也会连接
     * SSE/Streamable HTTP 服务。</p>
     */
    public synchronized List<McpToolInfo> listOfflineEligibleConfiguredTools() {
        List<AiClientToolMcpVO> stdioConfigs = repository.queryEnabledAiClientToolMcpVOList().stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.isNotBlank(item.getMcpId()))
                .filter(item -> McpServerDescriptor.TRANSPORT_TYPE_STDIO.equals(item.getTransportType()))
                .toList();

        List<AiClientToolMcpVO> missingConfigs = stdioConfigs.stream()
                .filter(item -> !runtimeCache.containsKey(item.getMcpId())
                        || !toolCache.containsKey(item.getMcpId()))
                .toList();
        preloadMcps(missingConfigs);

        List<McpToolInfo> tools = new ArrayList<>();
        for (AiClientToolMcpVO config : stdioConfigs) {
            for (McpToolInfo toolInfo : toolCache.getOrDefault(config.getMcpId(), List.of())) {
                if (toolInfo != null && toolInfo.isOfflineEligible()) {
                    tools.add(toolInfo);
                }
            }
        }
        return List.copyOf(tools);
    }

    /**
     * 注册或复用一个已经通过上层安全校验的用户远程 MCP。
     */
    public synchronized List<McpToolInfo> ensureExternalDescriptor(McpServerDescriptor descriptor) {
        userMcpEndpointPolicy.pin(descriptor);
        String mcpId = descriptor == null ? null : descriptor.getMcpId();
        if (StringUtils.isBlank(mcpId)) {
            throw new IllegalArgumentException("用户 MCP ID 不能为空");
        }
        McpClientRuntime cached = runtimeCache.get(mcpId);
        if (cached != null
                && cached.getDescriptor() != null
                && StringUtils.equals(cached.getDescriptor().getServerUrl(), descriptor.getServerUrl())
                && StringUtils.equals(cached.getDescriptor().getTransportType(), descriptor.getTransportType())) {
            return toolCache.getOrDefault(mcpId, List.of());
        }
        McpClientRuntime runtime = runtimeFactory.createRuntime(descriptor);
        List<McpToolInfo> tools = discoverTools(runtime);
        McpClientRuntime oldRuntime = runtimeCache.put(mcpId, runtime);
        toolCache.put(mcpId, List.copyOf(tools));
        toolCallbackCache.remove(mcpId);
        closeQuietly(oldRuntime, runtime);
        return tools;
    }

    public synchronized void removeExternalDescriptor(String mcpId) {
        McpClientRuntime runtime = runtimeCache.remove(mcpId);
        toolCache.remove(mcpId);
        toolCallbackCache.remove(mcpId);
        loadedConfigFingerprints.remove(mcpId);
        failedConfigFingerprints.remove(mcpId);
        userMcpEndpointPolicy.forget(mcpId);
        closeQuietly(runtime, null);
    }

    /**
     * 获取指定 MCP 列表上的工具列表。
     */
    public List<McpToolInfo> listToolsByMcpIds(List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }

        ensureMcpsLoaded(mcpIds);

        List<McpToolInfo> toolInfos = new ArrayList<>();
        for (String mcpId : new LinkedHashSet<>(mcpIds)) {
            List<McpToolInfo> oneMcpTools = toolCache.get(mcpId);
            if (oneMcpTools != null && !oneMcpTools.isEmpty()) {
                toolInfos.addAll(oneMcpTools);
            }
        }
        return toolInfos;
    }

    /**
     * 根据 mcpId 列表获取可复用的同步客户端。
     */
    public McpSyncClient[] getSyncClientsByMcpIds(List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return new McpSyncClient[0];
        }

        ensureMcpsLoaded(mcpIds);

        List<McpSyncClient> clients = new ArrayList<>();
        for (String mcpId : new LinkedHashSet<>(mcpIds)) {
            McpClientRuntime runtime = runtimeCache.get(mcpId);
            if (runtime != null && runtime.getSyncClient() != null) {
                clients.add(runtime.getSyncClient());
            }
        }
        return clients.toArray(new McpSyncClient[0]);
    }

    /**
     * 获取 fix 策略可直接复用的 ToolCallback。
     * 这里在运行时缓存里完成工具发现，并统一套上串行锁，避免 stdio 客户端被并发使用。
     */
    public List<ToolCallback> getToolCallbacksByMcpIds(List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }

        ensureMcpsLoaded(mcpIds);

        List<ToolCallback> callbacks = new ArrayList<>();
        for (String mcpId : new LinkedHashSet<>(mcpIds)) {
            if (StringUtils.isBlank(mcpId)) {
                continue;
            }
            callbacks.addAll(getOrCreateToolCallbacks(mcpId));
        }
        return callbacks;
    }

    /**
     * 统一执行 MCP 工具。
     */
    public ToolResultPayload executeTool(String mcpId, String toolName, Object args) {
        if (StringUtils.isBlank(mcpId) || StringUtils.isBlank(toolName)) {
            return buildFailureResult(toolName, "MCP id and tool name are required", false);
        }

        ensureMcpsLoaded(Collections.singletonList(mcpId));
        McpClientRuntime runtime = runtimeCache.get(mcpId);
        if (runtime == null) {
            log.error("MCP 客户端不存在，无法执行工具: mcpId={}, toolName={}", mcpId, toolName);
            return buildFailureResult(toolName, "MCP client is not available", false);
        }

        try {
            userMcpEndpointPolicy.validatePinned(runtime.getDescriptor());
            validateConfiguredDescriptor(runtime.getDescriptor());
            McpSchema.CallToolResult result = callToolWithRuntime(runtime, toolName, args);
            McpToolInfo toolInfo = toolCache.getOrDefault(mcpId, List.of()).stream()
                    .filter(candidate -> candidate != null && StringUtils.equals(candidate.getName(), toolName))
                    .findFirst()
                    .orElse(null);
            return formatToolResult(toolName, runtime.getDescriptor(), toolInfo, result);
        } catch (Exception e) {
            log.error("MCP 工具执行失败: mcpId={}, toolName={}, reason={}",
                    mcpId, toolName, e.getMessage(), e);
            return buildFailureResult(toolName, describeException(e), false);
        }
    }

    /**
     * 预热并缓存一批 MCP。
     */
    private void preloadMcps(List<AiClientToolMcpVO> mcpList) {
        if (mcpList == null || mcpList.isEmpty()) {
            return;
        }

        for (AiClientToolMcpVO mcpVO : mcpList) {
            if (mcpVO == null || StringUtils.isBlank(mcpVO.getMcpId())) {
                continue;
            }

            try {
                McpServerDescriptor descriptor = buildDescriptor(mcpVO);
                validateConfiguredDescriptor(descriptor);
                String mcpId = mcpVO.getMcpId();
                String fingerprint = configurationFingerprint(descriptor);
                if (isCurrentRuntime(mcpId, fingerprint) || isKnownUnavailable(mcpId, fingerprint)) {
                    continue;
                }
                evictChangedRuntime(mcpId);
                McpClientRuntime runtime = runtimeFactory.createRuntime(descriptor);
                List<McpToolInfo> tools = discoverTools(runtime);

                McpClientRuntime oldRuntime = runtimeCache.put(mcpId, runtime);
                toolCache.put(mcpId, tools);
                toolCallbackCache.remove(mcpId);
                loadedConfigFingerprints.put(mcpId, fingerprint);
                failedConfigFingerprints.remove(mcpId);

                closeQuietly(oldRuntime, runtime);
                log.info("MCP 预热成功: mcpId={}, toolCount={}", mcpId, tools.size());
            } catch (Exception e) {
                McpServerDescriptor descriptor = buildDescriptor(mcpVO);
                String fingerprint = configurationFingerprint(descriptor);
                failedConfigFingerprints.put(mcpVO.getMcpId(), fingerprint);
                log.error("MCP 预热失败: mcpId={}, errorType={}", mcpVO.getMcpId(), e.getClass().getSimpleName());
            }
        }
    }

    private boolean isCurrentRuntime(String mcpId, String fingerprint) {
        return runtimeCache.containsKey(mcpId)
                && toolCache.containsKey(mcpId)
                && StringUtils.equals(loadedConfigFingerprints.get(mcpId), fingerprint);
    }

    private boolean isKnownUnavailable(String mcpId, String fingerprint) {
        return StringUtils.equals(failedConfigFingerprints.get(mcpId), fingerprint);
    }

    private void evictChangedRuntime(String mcpId) {
        McpClientRuntime previous = runtimeCache.remove(mcpId);
        toolCache.remove(mcpId);
        toolCallbackCache.remove(mcpId);
        loadedConfigFingerprints.remove(mcpId);
        closeQuietly(previous, null);
    }

    private String configurationFingerprint(McpServerDescriptor descriptor) {
        String value = String.join("\n",
                StringUtils.defaultString(descriptor.getMcpId()),
                StringUtils.defaultString(descriptor.getTransportType()),
                StringUtils.defaultString(descriptor.getServerUrl()),
                StringUtils.defaultString(descriptor.getBaseUri()),
                StringUtils.defaultString(descriptor.getEndpoint()),
                String.valueOf(descriptor.getRequestTimeout()),
                StringUtils.defaultString(descriptor.getCommand()),
                String.join("\u001f", descriptor.getArgs() == null ? List.of() : descriptor.getArgs()),
                stableMap(descriptor.getEnv()),
                stableMap(descriptor.getHeaders()),
                String.valueOf(descriptor.getResumableStreams()),
                String.valueOf(descriptor.getOpenConnectionOnStartup()),
                StringUtils.defaultString(descriptor.getProtocolVersion()),
                StringUtils.defaultString(descriptor.getOauthAudience()),
                stableList(descriptor.getOauthScopes()),
                stableList(descriptor.getAllowedDomains()),
                stableList(descriptor.getToolAllowlist()),
                StringUtils.defaultString(descriptor.getCredentialRef()),
                StringUtils.defaultString(descriptor.getVersion()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String stableMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        return new TreeMap<>(source).entrySet().stream()
                .map(entry -> StringUtils.defaultString(entry.getKey()) + "="
                        + StringUtils.defaultString(entry.getValue()))
                .collect(Collectors.joining("\u001f"));
    }

    private String stableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(StringUtils::isNotBlank)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .collect(Collectors.joining("\u001f"));
    }

    /**
     * 基于已初始化客户端发现工具，并缓存为 Reactor 可直接消费的 McpToolInfo。
     */
    private List<McpToolInfo> discoverTools(McpClientRuntime runtime) {
        List<McpToolInfo> toolInfos = new ArrayList<>();
        runtime.getLock().lock();
        try {
            String cursor = null;
            do {
                McpSchema.ListToolsResult listToolsResult = StringUtils.isBlank(cursor)
                        ? runtime.getSyncClient().listTools()
                        : runtime.getSyncClient().listTools(cursor);

                if (listToolsResult == null || listToolsResult.tools() == null || listToolsResult.tools().isEmpty()) {
                    break;
                }

                for (McpSchema.Tool tool : listToolsResult.tools()) {
                    if (tool == null || !toolMetadataPolicy.isSafeToolName(tool.name())) {
                        log.warn("MCP tool ignored because its name violates metadata policy: mcpId={}, tool={}",
                                runtime.getDescriptor().getMcpId(), tool == null ? null : tool.name());
                        continue;
                    }
                    if (isToolAllowed(runtime.getDescriptor(), tool.name())) {
                        toolInfos.add(toToolInfo(runtime.getDescriptor(), tool));
                    } else {
                        log.warn("MCP tool ignored because it is not in the configured allowlist: mcpId={}, tool={}",
                                runtime.getDescriptor().getMcpId(), tool.name());
                    }
                }
                cursor = listToolsResult.nextCursor();
            } while (StringUtils.isNotBlank(cursor));
        } finally {
            runtime.getLock().unlock();
        }
        return toolInfos.stream()
                .sorted(java.util.Comparator.comparing(McpToolInfo::resolveExposedName))
                .toList();
    }

    /**
     * 将数据库配置转换为统一的运行时描述对象。
     */
    private McpServerDescriptor buildDescriptor(AiClientToolMcpVO mcpVO) {
        McpServerDescriptor.McpServerDescriptorBuilder builder = McpServerDescriptor.builder()
                .mcpId(mcpVO.getMcpId())
                .serverKey(mcpVO.getMcpId())
                .transportType(mcpVO.getTransportType())
                .origin(McpToolOrigin.CONFIGURED)
                .requestTimeout(mcpVO.getRequestTimeout())
                .protocolVersion(StringUtils.defaultIfBlank(mcpVO.getProtocolVersion(), "2025-03-26"))
                .oauthAudience(mcpVO.getOauthAudience())
                .oauthScopes(mcpVO.getOauthScopes() == null ? List.of() : mcpVO.getOauthScopes())
                .allowedDomains(mcpVO.getAllowedDomains() == null ? List.of() : mcpVO.getAllowedDomains())
                .toolAllowlist(mcpVO.getToolAllowlist() == null ? List.of() : mcpVO.getToolAllowlist())
                .credentialRef(mcpVO.getCredentialRef())
                .version(StringUtils.defaultIfBlank(mcpVO.getVersion(), "v1"))
                .configHash(mcpVO.getConfigHash());

        if (McpServerDescriptor.TRANSPORT_TYPE_SSE.equals(mcpVO.getTransportType())) {
            AiClientToolMcpVO.TransportConfigSse configSse = mcpVO.getTransportConfigSse();
            String baseUri = configSse != null ? configSse.getBaseUri() : "";
            String endpoint = configSse != null ? configSse.getSseEndpoint() : "";
            String serverUrl = buildServerUrl(baseUri, endpoint);
            return finalizeConfiguredDescriptor(builder
                    .serverUrl(serverUrl)
                    .baseUri(baseUri)
                    .endpoint(endpoint)
                    .build());
        }

        if (McpServerDescriptor.TRANSPORT_TYPE_STDIO.equals(mcpVO.getTransportType())) {
            AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = mcpVO.getTransportConfigStdio();
            AiClientToolMcpVO.TransportConfigStdio.Stdio stdio = null;
            if (transportConfigStdio != null && transportConfigStdio.getStdio() != null) {
                stdio = transportConfigStdio.getStdio().get(mcpVO.getMcpName());
                if (stdio == null && transportConfigStdio.getStdio().size() == 1) {
                    stdio = transportConfigStdio.getStdio().values().iterator().next();
                }
            }

            return finalizeConfiguredDescriptor(builder
                    .serverUrl("stdio://" + mcpVO.getMcpId())
                    .command(stdio != null ? stdio.getCommand() : null)
                    .args(stdio != null && stdio.getArgs() != null ? stdio.getArgs() : Collections.emptyList())
                    .env(stdio != null && stdio.getEnv() != null ? stdio.getEnv() : Collections.emptyMap())
                    .build());
        }

        if (McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP.equals(mcpVO.getTransportType())) {
            AiClientToolMcpVO.TransportConfigStreamableHttp streamableHttp = mcpVO.getTransportConfigStreamableHttp();
            String baseUri = streamableHttp != null ? streamableHttp.getBaseUri() : "";
            String endpoint = streamableHttp != null ? streamableHttp.getEndpoint() : "/mcp";
            Map<String, String> headers = streamableHttp != null && streamableHttp.getHeaders() != null
                    ? streamableHttp.getHeaders()
                    : Collections.emptyMap();
            Boolean resumableStreams = streamableHttp != null ? streamableHttp.getResumableStreams() : false;
            Boolean openConnectionOnStartup = streamableHttp != null ? streamableHttp.getOpenConnectionOnStartup() : true;
            String serverUrl = buildServerUrl(baseUri, endpoint);
            return finalizeConfiguredDescriptor(builder
                    .serverUrl(serverUrl)
                    .baseUri(baseUri)
                    .endpoint(endpoint)
                    .headers(headers)
                    .resumableStreams(Boolean.TRUE.equals(resumableStreams))
                    .openConnectionOnStartup(!Boolean.FALSE.equals(openConnectionOnStartup))
                    .build());
        }

        return finalizeConfiguredDescriptor(builder
                .serverUrl(mcpVO.getTransportConfig())
                .build());
    }

    private McpServerDescriptor finalizeConfiguredDescriptor(McpServerDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("MCP descriptor is required");
        }
        descriptor.setOauthScopes(normalizeList(descriptor.getOauthScopes()));
        descriptor.setToolAllowlist(normalizeList(descriptor.getToolAllowlist()));
        List<String> allowedDomains = normalizeList(descriptor.getAllowedDomains());
        if (allowedDomains.isEmpty() && isHttpTransport(descriptor)) {
            allowedDomains = List.of(requireHttpHost(descriptor.getServerUrl()));
        }
        descriptor.setAllowedDomains(allowedDomains);
        if (StringUtils.isBlank(descriptor.getConfigHash())) {
            descriptor.setConfigHash("sha256:" + configurationFingerprint(descriptor));
        }
        return descriptor;
    }

    private boolean isToolAllowed(McpServerDescriptor descriptor, String remoteToolName) {
        if (descriptor == null || descriptor.getToolAllowlist() == null
                || descriptor.getToolAllowlist().isEmpty()) {
            return true;
        }
        String canonical = McpToolInfo.canonicalExposedName(descriptor.getMcpId(), remoteToolName);
        return descriptor.getToolAllowlist().stream()
                .anyMatch(item -> StringUtils.equals(item, remoteToolName)
                        || StringUtils.equals(item, canonical));
    }

    private void validateConfiguredDescriptor(McpServerDescriptor descriptor) {
        if (descriptor == null || descriptor.getOrigin() != McpToolOrigin.CONFIGURED) {
            return;
        }
        if (StringUtils.isBlank(descriptor.getProtocolVersion())) {
            throw new IllegalArgumentException("MCP protocol version is required");
        }
        if (descriptor.getOauthScopes() != null && !descriptor.getOauthScopes().isEmpty()
                && StringUtils.isBlank(descriptor.getOauthAudience())) {
            throw new IllegalArgumentException("MCP OAuth scopes require an audience");
        }
        if (StringUtils.isNotBlank(descriptor.getCredentialRef())
                && !descriptor.getCredentialRef().matches("vault:[A-Za-z0-9._:/-]{1,200}")) {
            throw new IllegalArgumentException("MCP credential reference must use the vault: scheme");
        }
        if (descriptor.getHeaders() != null && descriptor.getHeaders().keySet().stream()
                .filter(Objects::nonNull)
                .map(key -> key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT))
                .anyMatch(key -> key.contains("authorization") || key.contains("token")
                        || key.contains("apikey") || key.contains("secret") || key.contains("credential"))) {
            throw new IllegalArgumentException("MCP token passthrough is forbidden; use credentialRef");
        }
        if (isHttpTransport(descriptor)) {
            String host = requireHttpHost(descriptor.getServerUrl());
            if (descriptor.getAllowedDomains() == null || descriptor.getAllowedDomains().isEmpty()
                    || descriptor.getAllowedDomains().stream().noneMatch(allowed -> domainMatches(host, allowed))) {
                throw new IllegalArgumentException("MCP endpoint is outside the configured allowed domains");
            }
        }
    }

    private boolean isHttpTransport(McpServerDescriptor descriptor) {
        return descriptor != null && (McpServerDescriptor.TRANSPORT_TYPE_SSE.equals(descriptor.getTransportType())
                || McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP.equals(descriptor.getTransportType()));
    }

    private String requireHttpHost(String rawUrl) {
        URI uri = URI.create(StringUtils.trimToEmpty(rawUrl));
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())
                || StringUtils.isBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid configured MCP HTTP endpoint");
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private boolean domainMatches(String host, String configuredDomain) {
        String allowed = StringUtils.trimToEmpty(configuredDomain).toLowerCase(Locale.ROOT);
        if (allowed.startsWith("*.")) {
            String suffix = allowed.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return StringUtils.equals(host, allowed);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 拼接完整服务地址，便于日志排查。
     */
    private String buildServerUrl(String baseUri, String endpoint) {
        String safeBaseUri = StringUtils.defaultString(baseUri);
        String safeEndpoint = StringUtils.defaultString(endpoint);
        if (StringUtils.isBlank(safeBaseUri)) {
            return safeEndpoint;
        }
        if (StringUtils.isBlank(safeEndpoint)) {
            return safeBaseUri;
        }
        if (safeBaseUri.endsWith("/") && safeEndpoint.startsWith("/")) {
            return safeBaseUri.substring(0, safeBaseUri.length() - 1) + safeEndpoint;
        }
        if (!safeBaseUri.endsWith("/") && !safeEndpoint.startsWith("/")) {
            return safeBaseUri + "/" + safeEndpoint;
        }
        return safeBaseUri + safeEndpoint;
    }

    /**
     * 清理已经被禁用的 MCP 缓存。
     */
    private void syncDisabledMcps(Set<String> enabledMcpIds) {
        Set<String> staleMcpIds = new LinkedHashSet<>(runtimeCache.keySet());
        staleMcpIds.addAll(loadedConfigFingerprints.keySet());
        staleMcpIds.addAll(failedConfigFingerprints.keySet());
        staleMcpIds.removeAll(enabledMcpIds);

        for (String staleMcpId : staleMcpIds) {
            McpClientRuntime staleRuntime = runtimeCache.remove(staleMcpId);
            toolCache.remove(staleMcpId);
            toolCallbackCache.remove(staleMcpId);
            loadedConfigFingerprints.remove(staleMcpId);
            failedConfigFingerprints.remove(staleMcpId);
            userMcpEndpointPolicy.forget(staleMcpId);
            removeClientBinding(staleMcpId);
            closeQuietly(staleRuntime, null);
            log.info("MCP 已从缓存移除: mcpId={}", staleMcpId);
        }
    }

    /**
     * 从客户端绑定缓存中移除失效的 MCP。
     */
    private void removeClientBinding(String mcpId) {
        clientMcpIdCache.replaceAll((clientId, mcpIds) -> mcpIds.stream()
                .filter(id -> !StringUtils.equals(id, mcpId))
                .toList());
    }

    /**
     * 确保全局 MCP 已预热。
     */
    private void ensureGlobalMcpsLoaded() {
        if (globalEnabledMcpIds.isEmpty()) {
            preloadAllEnabledMcps();
        }
    }

    /**
     * 确保指定 MCP 已完成预热。
     */
    private void ensureMcpsLoaded(List<String> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return;
        }
        List<String> missingIds = mcpIds.stream()
                .filter(StringUtils::isNotBlank)
                .filter(mcpId -> !runtimeCache.containsKey(mcpId) || !toolCache.containsKey(mcpId))
                .distinct()
                .toList();

        if (!missingIds.isEmpty()) {
            preloadAllEnabledMcps();
        }
    }

    /**
     * 将 SDK 工具定义转成内部统一工具描述。
     */
    private McpToolInfo toToolInfo(McpServerDescriptor descriptor, McpSchema.Tool tool) {
        String parameters = tool.inputSchema() == null ? "{}" : writeAsJson(tool.inputSchema());
        String outputSchema = tool.outputSchema() == null ? "{}" : writeAsJson(tool.outputSchema());
        return McpToolInfo.builder()
                .mcpId(descriptor.getMcpId())
                .name(tool.name())
                .exposedName(McpToolInfo.canonicalExposedName(descriptor.getMcpId(), tool.name()))
                .desc(toolMetadataPolicy.sanitizeDescription(tool.description(), tool.title()))
                .parameters(parameters)
                .outputSchema(outputSchema)
                .transportType(descriptor.getTransportType())
                .origin(descriptor.getOrigin())
                .serverKey(descriptor.resolveServerKey())
                .descriptor(descriptor)
                .build();
    }

    /**
     * 按 mcpId 懒加载并缓存 ToolCallback。
     */
    private List<ToolCallback> getOrCreateToolCallbacks(String mcpId) {
        return toolCallbackCache.computeIfAbsent(mcpId, this::buildToolCallbacks);
    }

    /**
     * 基于共享运行时生成一次性 ToolCallback，并在执行期统一复用。
     * 这样可以避免 SyncMcpToolCallbackProvider 在每次请求时重复 listTools。
     */
    private List<ToolCallback> buildToolCallbacks(String mcpId) {
        McpClientRuntime runtime = runtimeCache.get(mcpId);
        List<McpToolInfo> toolInfos = toolCache.get(mcpId);
        if (runtime == null || runtime.getDescriptor() == null || toolInfos == null || toolInfos.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolCallback> callbacks = new ArrayList<>(toolInfos.size());
        for (McpToolInfo toolInfo : toolInfos) {
            callbacks.add(new RegistryBackedToolCallback(this, toolInfo));
        }
        return List.copyOf(callbacks);
    }

    /**
     * 根据传输协议选择调用策略。
     * SSE和Stream 继续复用共享客户端；STDIO 每次创建临时运行时，用完即关，避免长连接 transport 状态失效。
     */
    private McpSchema.CallToolResult callToolWithRuntime(McpClientRuntime runtime, String toolName, Object args) {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, normalizeArguments(args));
        if (isStdioRuntime(runtime)) {
            return callToolWithTransientRuntime(runtime.getDescriptor(), request);
        }
        return callToolWithSharedRuntime(runtime, request);
    }

    /**
     * 共享运行时调用，适用于 SSE 等可稳定复用的连接。
     */
    private McpSchema.CallToolResult callToolWithSharedRuntime(McpClientRuntime runtime, McpSchema.CallToolRequest request) {
        runtime.getLock().lock();
        try {
            return runtime.getSyncClient().callTool(request);
        } finally {
            runtime.getLock().unlock();
        }
    }

    /**
     * STDIO 运行时调用采用短生命周期策略，避免 transport 复用后内部调度器失效。
     */
    private McpSchema.CallToolResult callToolWithTransientRuntime(McpServerDescriptor descriptor, McpSchema.CallToolRequest request) {
        McpClientRuntime transientRuntime = runtimeFactory.createRuntime(copyDescriptor(descriptor));
        try {
            return transientRuntime.getSyncClient().callTool(request);
        } finally {
            closeRuntimeQuietly(transientRuntime);
        }
    }

    /**
     * 判断是否为 stdio 运行时。
     */
    private boolean isStdioRuntime(McpClientRuntime runtime) {
        return runtime != null
                && runtime.getDescriptor() != null
                && StringUtils.equals(runtime.getDescriptor().getTransportType(), McpServerDescriptor.TRANSPORT_TYPE_STDIO);
    }

    /**
     * 复制服务描述，避免临时运行时污染共享配置对象。
     */
    private McpServerDescriptor copyDescriptor(McpServerDescriptor descriptor) {
        return McpServerDescriptor.builder()
                .mcpId(descriptor.getMcpId())
                .serverUrl(descriptor.getServerUrl())
                .transportType(descriptor.getTransportType())
                .origin(descriptor.getOrigin())
                .serverKey(descriptor.getServerKey())
                .baseUri(descriptor.getBaseUri())
                .endpoint(descriptor.getEndpoint())
                .requestTimeout(descriptor.getRequestTimeout())
                .protocolVersion(descriptor.getProtocolVersion())
                .oauthAudience(descriptor.getOauthAudience())
                .oauthScopes(descriptor.getOauthScopes() == null ? List.of() : new ArrayList<>(descriptor.getOauthScopes()))
                .allowedDomains(descriptor.getAllowedDomains() == null ? List.of() : new ArrayList<>(descriptor.getAllowedDomains()))
                .toolAllowlist(descriptor.getToolAllowlist() == null ? List.of() : new ArrayList<>(descriptor.getToolAllowlist()))
                .credentialRef(descriptor.getCredentialRef())
                .version(descriptor.getVersion())
                .configHash(descriptor.getConfigHash())
                .command(descriptor.getCommand())
                .args(descriptor.getArgs() == null ? Collections.emptyList() : new ArrayList<>(descriptor.getArgs()))
                .env(descriptor.getEnv() == null ? Collections.emptyMap() : new LinkedHashMap<>(descriptor.getEnv()))
                .headers(descriptor.getHeaders() == null ? Collections.emptyMap() : new LinkedHashMap<>(descriptor.getHeaders()))
                .resumableStreams(Boolean.TRUE.equals(descriptor.getResumableStreams()))
                .openConnectionOnStartup(!Boolean.FALSE.equals(descriptor.getOpenConnectionOnStartup()))
                .build();
    }

    /**
     * 规范化工具参数，统一转换成 Map 结构。
     */
    private Map<String, Object> normalizeArguments(Object args) {
        if (args == null) {
            return Collections.emptyMap();
        }
        if (args instanceof Map<?, ?> mapArgs) {
            return JsonUtils.parseObject(JsonUtils.toJson(mapArgs), new TypeReference<Map<String, Object>>() {
            });
        }
        if (args instanceof String str && isValidJsonObject(str)) {
            return JsonUtils.parseObject(str, new TypeReference<Map<String, Object>>() {
            });
        }
        return JsonUtils.parseObject(JsonUtils.toJson(args), new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 判断字符串是否为 JSON 对象。
     */
    private boolean isValidJsonObject(String value) {
        try {
            return JsonUtils.mapper().readTree(value).isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 将 MCP 返回结果转换为统一的 typed 工具结果。
     * 成功时继续沿用原有 observation；协议错误和无有效内容必须显式标记失败，
     * 避免上层把错误字符串误记为成功证据。
     */
    private ToolResultPayload formatToolResult(String toolName,
                                               McpServerDescriptor descriptor,
                                               McpToolInfo toolInfo,
                                               McpSchema.CallToolResult result) {
        String mcpId = descriptor == null ? "unknown" : descriptor.getMcpId();
        if (result == null) {
            log.error("MCP 工具执行返回空结果: mcpId={}, toolName={}", mcpId, toolName);
            return buildFailureResult(toolName, "MCP returned a null result", false);
        }

        if (Boolean.TRUE.equals(result.isError())) {
            String errorDetail = extractErrorDetail(result);
            log.error("MCP 工具返回错误结果: mcpId={}, toolName={}, result={}",
                    mcpId, toolName, writeAsJson(result));
            return buildFailureResult(toolName, errorDetail, true);
        }

        ToolResultPayload schemaFailure = validateOutputSchema(toolName, toolInfo, result);
        if (schemaFailure != null) {
            return schemaFailure;
        }

        String textResult = extractTextContent(result.content());
        if (StringUtils.isNotBlank(textResult)) {
            return ToolResultPayload.text(textResult);
        }
        if (hasUsableStructuredContent(result.structuredContent())) {
            return ToolResultPayload.text(writeAsJson(result.structuredContent()));
        }
        if (hasUsableContent(result.content())) {
            return ToolResultPayload.text(writeAsJson(result.content()));
        }
        log.error("MCP 工具执行未返回有效内容: mcpId={}, toolName={}, result={}",
                mcpId, toolName, writeAsJson(result));
        return buildFailureResult(toolName, "MCP returned no usable content", false);
    }

    /**
     * Validate structured MCP output before exposing it to the model. A
     * declared output schema with no structured payload is a protocol error;
     * schema-less MCP tools retain the legacy text/content behavior.
     */
    private ToolResultPayload validateOutputSchema(String toolName,
                                                    McpToolInfo toolInfo,
                                                    McpSchema.CallToolResult result) {
        if (toolInfo == null || StringUtils.isBlank(toolInfo.getOutputSchema())
                || "{}".equals(toolInfo.getOutputSchema().trim())) {
            return null;
        }
        if (result.structuredContent() == null) {
            return buildFailureResult(toolName,
                    "MCP output schema requires structured content", false);
        }
        try {
            Map<String, Object> schema = JsonUtils.parseObject(
                    toolInfo.getOutputSchema(), new TypeReference<Map<String, Object>>() { });
            ToolInputSchemaValidator.ValidationResult validation =
                    new ToolInputSchemaValidator().validate(schema, result.structuredContent());
            if (!validation.valid()) {
                return buildFailureResult(toolName,
                        "MCP output schema validation failed: " + validation.message(), false);
            }
            return null;
        } catch (Exception error) {
            return buildFailureResult(toolName,
                    "MCP output schema is invalid", false);
        }
    }

    private boolean hasUsableContent(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return false;
        }
        for (McpSchema.Content content : contents) {
            if (content == null) {
                continue;
            }
            if (content instanceof McpSchema.TextContent textContent) {
                if (StringUtils.isNotBlank(textContent.text())) {
                    return true;
                }
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasUsableStructuredContent(Object structuredContent) {
        if (structuredContent == null) {
            return false;
        }
        if (structuredContent instanceof CharSequence sequence) {
            return StringUtils.isNotBlank(sequence.toString());
        }
        if (structuredContent instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (structuredContent instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (structuredContent.getClass().isArray()) {
            return Array.getLength(structuredContent) > 0;
        }
        return true;
    }

    /**
     * 从 MCP content 中提取文本块。
     */
    private String extractTextContent(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent && StringUtils.isNotBlank(textContent.text())) {
                if (textBuilder.length() > 0) {
                    textBuilder.append(System.lineSeparator());
                }
                textBuilder.append(textContent.text());
            }
        }
        return textBuilder.toString();
    }

    /**
     * 从错误结果中提取更可读的报错信息。
     */
    private String extractErrorDetail(McpSchema.CallToolResult result) {
        String textContent = extractTextContent(result.content());
        if (StringUtils.isNotBlank(textContent)) {
            return textContent;
        }
        if (result.structuredContent() != null) {
            return writeAsJson(result.structuredContent());
        }
        if (result.content() != null && !result.content().isEmpty()) {
            return writeAsJson(result.content());
        }
        return writeAsJson(result);
    }

    /**
     * 统一生成 typed 失败结果。模型 observation 默认维持旧的通用错误文案；
     * MCP 明确返回 isError 时保留其原有错误详情，便于模型修正调用。
     */
    private ToolResultPayload buildFailureResult(String toolName,
                                                 String errorDetail,
                                                 boolean exposeDetailInObservation) {
        String normalizedToolName = StringUtils.defaultIfBlank(toolName, "Unknown");
        String baseMessage = "Tool" + normalizedToolName + " Error.";
        String normalizedDetail = StringUtils.trimToEmpty(errorDetail);
        String observation = exposeDetailInObservation && StringUtils.isNotBlank(normalizedDetail)
                ? baseMessage + " " + normalizedDetail
                : baseMessage;
        return ToolResultPayload.failure(
                observation,
                observation,
                null,
                StringUtils.defaultIfBlank(normalizedDetail, baseMessage)
        );
    }

    private String describeException(Exception error) {
        if (error == null) {
            return "MCP transport failure";
        }
        return StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
    }

    /**
     * 统一的 JSON 序列化兜底。
     */
    private String writeAsJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("MCP 对象序列化失败，降级使用 toString: type={}, reason={}",
                    value.getClass().getName(), e.getMessage());
            return String.valueOf(value);
        }
    }

    /**
     * 安静关闭旧运行时，避免刷新时泄漏连接。
     */
    private void closeQuietly(McpClientRuntime oldRuntime, McpClientRuntime newRuntime) {
        if (oldRuntime == null || oldRuntime == newRuntime || oldRuntime.getSyncClient() == null) {
            return;
        }
        closeRuntimeQuietly(oldRuntime);
    }

    /**
     * 安静关闭运行时。
     */
    private void closeRuntimeQuietly(McpClientRuntime runtime) {
        if (runtime == null || runtime.getSyncClient() == null) {
            return;
        }
        try {
            runtime.getSyncClient().closeGracefully();
        } catch (Exception e) {
            log.warn("关闭旧 MCP 客户端失败: mcpId={}, reason={}",
                    runtime.getDescriptor() != null ? runtime.getDescriptor().getMcpId() : "unknown",
                    e.getMessage());
        }
    }
}
