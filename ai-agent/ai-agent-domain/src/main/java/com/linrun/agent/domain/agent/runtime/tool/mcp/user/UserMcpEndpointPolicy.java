package com.linrun.agent.domain.agent.runtime.tool.mcp.user;

import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import org.apache.commons.lang3.StringUtils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fail-closed validation for user-managed remote MCP endpoints.
 *
 * <p>The same check is applied when a configuration is saved, when its runtime
 * is opened, and before a cached user runtime executes a tool. Re-resolving the
 * hostname on execution prevents a previously public hostname from silently
 * becoming a private target after registration.</p>
 */
public final class UserMcpEndpointPolicy {

    @FunctionalInterface
    public interface HostnameResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostnameResolver hostnameResolver;
    /**
     * A user endpoint is pinned to the public address set observed while its
     * runtime is registered.  Requiring the exact set on each execution turns
     * a later DNS answer change into a fail-closed reconnect, rather than
     * silently reusing the endpoint under a different network identity.
     */
    private final ConcurrentMap<String, EndpointPin> endpointPins = new ConcurrentHashMap<>();

    public UserMcpEndpointPolicy() {
        this(InetAddress::getAllByName);
    }

    public UserMcpEndpointPolicy(HostnameResolver hostnameResolver) {
        this.hostnameResolver = hostnameResolver == null ? InetAddress::getAllByName : hostnameResolver;
    }

    public void validate(UserMcpConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MCP 配置不能为空");
        }
        validate(config.getServerUrl(), config.getTransportType());
    }

    public void validate(McpServerDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("用户 MCP 描述不能为空");
        }
        if (descriptor.getOrigin() != McpToolOrigin.USER_EXTENSION) {
            return;
        }
        if (StringUtils.isBlank(descriptor.getMcpId())
                || !descriptor.getMcpId().matches("user:[0-9]+:[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("用户 MCP 标识非法");
        }
        validate(descriptor.getServerUrl(), descriptor.getTransportType());
    }

    public void validate(String rawUrl, String rawTransportType) {
        resolvePublicEndpoint(rawUrl, rawTransportType);
    }

    /** Pins a user endpoint after validating its transport, URI and public DNS answer. */
    public void pin(McpServerDescriptor descriptor) {
        if (!isUserDescriptor(descriptor)) {
            return;
        }
        validate(descriptor);
        ResolvedEndpoint resolved = resolvePublicEndpoint(descriptor.getServerUrl(), descriptor.getTransportType());
        endpointPins.put(descriptor.getMcpId(), EndpointPin.from(descriptor, resolved));
    }

    /**
     * Re-resolves a user endpoint before a networked tool call and requires the
     * result to match the registration pin.  Operators must explicitly
     * re-register the endpoint after a legitimate DNS migration.
     */
    public void validatePinned(McpServerDescriptor descriptor) {
        if (!isUserDescriptor(descriptor)) {
            return;
        }
        if (StringUtils.isBlank(descriptor.getMcpId())) {
            throw new IllegalArgumentException("用户 MCP 标识非法");
        }
        ResolvedEndpoint resolved = resolvePublicEndpoint(descriptor.getServerUrl(), descriptor.getTransportType());
        EndpointPin pin = endpointPins.get(descriptor.getMcpId());
        if (pin == null) {
            throw new IllegalArgumentException("用户 MCP 尚未完成 DNS 固定，拒绝执行");
        }
        if (!pin.matches(descriptor, resolved)) {
            throw new IllegalArgumentException("用户 MCP DNS 解析已变化，必须重新注册后再执行");
        }
    }

    /** Removes a stale pin whenever its user MCP runtime is removed. */
    public void forget(String mcpId) {
        if (StringUtils.isNotBlank(mcpId)) {
            endpointPins.remove(mcpId);
        }
    }

    private ResolvedEndpoint resolvePublicEndpoint(String rawUrl, String rawTransportType) {
        String transportType = StringUtils.trimToEmpty(rawTransportType).toLowerCase();
        if (!McpServerDescriptor.TRANSPORT_TYPE_SSE.equals(transportType)
                && !McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP.equals(transportType)) {
            throw new IllegalArgumentException("用户 MCP 仅支持 SSE 或 Streamable HTTP，STDIO 已永久关闭");
        }
        URI uri;
        try {
            uri = URI.create(StringUtils.trimToEmpty(rawUrl));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("MCP 地址格式非法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("MCP 地址仅支持 HTTP/HTTPS");
        }
        if (StringUtils.isBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP 地址格式非法");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("MCP 地址端口非法");
        }
        try {
            InetAddress[] addresses = hostnameResolver.resolve(uri.getHost());
            if (addresses == null || addresses.length == 0) {
                throw new IllegalArgumentException("无法解析 MCP 地址");
            }
            for (InetAddress address : addresses) {
                if (isNonPublic(address)) {
                    throw new IllegalArgumentException("MCP 地址不能指向本机或内网");
                }
            }
            List<String> publicAddresses = Arrays.stream(addresses)
                    .map(InetAddress::getHostAddress)
                    .distinct()
                    .sorted()
                    .toList();
            return new ResolvedEndpoint(uri.getHost().toLowerCase(), effectivePort(uri), transportType, publicAddresses);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("无法解析 MCP 地址");
        }
    }

    private boolean isUserDescriptor(McpServerDescriptor descriptor) {
        return descriptor != null && descriptor.getOrigin() == McpToolOrigin.USER_EXTENSION;
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isNonPublic(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
        }
        return true;
    }

    private record ResolvedEndpoint(String host, int port, String transportType, List<String> addresses) {
    }

    private record EndpointPin(String host, int port, String transportType, List<String> addresses) {
        private static EndpointPin from(McpServerDescriptor descriptor, ResolvedEndpoint resolved) {
            return new EndpointPin(resolved.host(), resolved.port(), resolved.transportType(), resolved.addresses());
        }

        private boolean matches(McpServerDescriptor descriptor, ResolvedEndpoint resolved) {
            return StringUtils.equals(host, resolved.host())
                    && port == resolved.port()
                    && StringUtils.equals(transportType, resolved.transportType())
                    && addresses.equals(resolved.addresses());
        }
    }
}
