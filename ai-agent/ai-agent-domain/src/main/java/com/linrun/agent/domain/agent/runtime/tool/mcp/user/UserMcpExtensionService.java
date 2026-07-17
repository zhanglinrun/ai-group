package com.linrun.agent.domain.agent.runtime.tool.mcp.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserMcpExtensionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<UserMcpConfig>> CONFIG_LIST = new TypeReference<>() {
    };

    private final McpRegistry mcpRegistry;
    private final Map<String, Object> ownerLocks = new ConcurrentHashMap<>();

    @Value("${autobots.autoagent.user-extensions.directory:runtime/user-extensions}")
    private String extensionsDirectory;

    public UserMcpExtensionService(McpRegistry mcpRegistry) {
        this.mcpRegistry = mcpRegistry;
    }

    public List<UserMcpConfig> list(String ownerId) {
        synchronized (lock(ownerId)) {
            return read(ownerId);
        }
    }

    public UserMcpConfig save(String ownerId, UserMcpConfig input) {
        synchronized (lock(ownerId)) {
            validate(input);
            List<UserMcpConfig> configs = new ArrayList<>(read(ownerId));
            String id = StringUtils.defaultIfBlank(input.getId(), UUID.randomUUID().toString());
            if (configs.stream().anyMatch(item -> !id.equals(item.getId())
                    && item.getName().equalsIgnoreCase(input.getName().trim()))) {
                throw new IllegalArgumentException("MCP 名称已存在");
            }
            UserMcpConfig saved = UserMcpConfig.builder()
                    .id(id)
                    .name(input.getName().trim())
                    .serverUrl(input.getServerUrl().trim())
                    .transportType(normalizeTransport(input.getTransportType()))
                    .enabled(input.isEnabled())
                    .toolCount(0)
                    .build();
            if (saved.isEnabled()) {
                saved.setToolCount(discover(ownerId, saved).size());
            }
            configs.removeIf(item -> id.equals(item.getId()));
            configs.add(saved);
            write(ownerId, configs);
            return saved;
        }
    }

    public UserMcpConfig setEnabled(String ownerId, String id, boolean enabled) {
        synchronized (lock(ownerId)) {
            List<UserMcpConfig> configs = new ArrayList<>(read(ownerId));
            UserMcpConfig target = require(configs, id);
            target.setEnabled(enabled);
            if (enabled) {
                target.setToolCount(discover(ownerId, target).size());
            } else {
                target.setToolCount(0);
                mcpRegistry.removeExternalDescriptor(runtimeId(ownerId, id));
            }
            write(ownerId, configs);
            return target;
        }
    }

    public void delete(String ownerId, String id) {
        synchronized (lock(ownerId)) {
            List<UserMcpConfig> configs = new ArrayList<>(read(ownerId));
            require(configs, id);
            configs.removeIf(item -> id.equals(item.getId()));
            write(ownerId, configs);
            mcpRegistry.removeExternalDescriptor(runtimeId(ownerId, id));
        }
    }

    public List<McpToolInfo> discoverEnabledTools(String ownerId) {
        List<McpToolInfo> result = new ArrayList<>();
        for (UserMcpConfig config : list(ownerId)) {
            if (!config.isEnabled()) {
                continue;
            }
            try {
                result.addAll(discover(ownerId, config));
            } catch (RuntimeException ignored) {
                // A broken user MCP must not block the Agent's built-in tools.
            }
        }
        return result;
    }

    private List<McpToolInfo> discover(String ownerId, UserMcpConfig config) {
        validate(config);
        String transport = normalizeTransport(config.getTransportType());
        URI uri = URI.create(config.getServerUrl());
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = StringUtils.defaultIfBlank(uri.getRawPath(),
                McpServerDescriptor.TRANSPORT_TYPE_SSE.equals(transport) ? "/sse" : "/mcp");
        McpServerDescriptor descriptor = McpServerDescriptor.builder()
                .mcpId(runtimeId(ownerId, config.getId()))
                .serverKey(runtimeId(ownerId, config.getId()))
                .serverUrl(config.getServerUrl())
                .transportType(transport)
                .origin(McpToolOrigin.USER_EXTENSION)
                .baseUri(baseUri)
                .endpoint(endpoint)
                .requestTimeout(30)
                .openConnectionOnStartup(false)
                .build();
        return mcpRegistry.ensureExternalDescriptor(descriptor);
    }

    private void validate(UserMcpConfig config) {
        if (config == null || StringUtils.isAnyBlank(config.getName(), config.getServerUrl())) {
            throw new IllegalArgumentException("MCP 名称和服务地址不能为空");
        }
        String transport = normalizeTransport(config.getTransportType());
        if (!McpServerDescriptor.TRANSPORT_TYPE_SSE.equals(transport)
                && !McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP.equals(transport)) {
            throw new IllegalArgumentException("用户 MCP 仅支持 SSE 或 Streamable HTTP");
        }
        validatePublicUrl(config.getServerUrl());
    }

    private void validatePublicUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("MCP 地址仅支持 HTTP/HTTPS");
            }
            if (StringUtils.isBlank(uri.getHost()) || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("MCP 地址格式非法");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("MCP 地址不能指向本机或内网");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 MCP 地址");
        }
    }

    private String normalizeTransport(String value) {
        return StringUtils.defaultIfBlank(value, McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
                .trim()
                .toLowerCase();
    }

    private UserMcpConfig require(List<UserMcpConfig> configs, String id) {
        return configs.stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("用户 MCP 不存在"));
    }

    private List<UserMcpConfig> read(String ownerId) {
        Path path = configPath(ownerId);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(path.toFile(), CONFIG_LIST);
        } catch (IOException e) {
            throw new IllegalStateException("读取用户 MCP 配置失败", e);
        }
    }

    private void write(String ownerId, List<UserMcpConfig> configs) {
        Path target = configPath(ownerId);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), configs);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存用户 MCP 配置失败", e);
        }
    }

    private Path configPath(String ownerId) {
        if (ownerId == null || !ownerId.matches("\\d+")) {
            throw new IllegalArgumentException("ownerId 非法");
        }
        Path base = Path.of(extensionsDirectory).toAbsolutePath().normalize();
        Path path = base.resolve(ownerId).resolve("mcp.json").normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("用户 MCP 路径非法");
        }
        return path;
    }

    private String runtimeId(String ownerId, String id) {
        return "user:" + ownerId + ":" + id;
    }

    private Object lock(String ownerId) {
        return ownerLocks.computeIfAbsent(ownerId, ignored -> new Object());
    }
}
