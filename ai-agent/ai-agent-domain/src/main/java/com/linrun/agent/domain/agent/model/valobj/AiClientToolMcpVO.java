package com.linrun.agent.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP客户端配置，值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientToolMcpVO {

    /**
     * MCP ID
     */
    private String mcpId;

    /**
     * MCP名称
     */
    private String mcpName;

    /**
     * 传输类型(sse/stdio/streamable_http)
     */
    private String transportType;

    /**
     * 传输配置(sse/stdio/streamable_http)
     */
    private String transportConfig;

    /**
     * 请求超时时间(分钟)
     */
    private Integer requestTimeout;

    /** Admin-controlled MCP governance metadata; never model-facing. */
    @Builder.Default
    private String protocolVersion = "2025-03-26";

    private String oauthAudience;

    @Builder.Default
    private List<String> oauthScopes = List.of();

    @Builder.Default
    private List<String> allowedDomains = List.of();

    @Builder.Default
    private List<String> toolAllowlist = List.of();

    /** Reference to a server-side secret, never the credential value itself. */
    private String credentialRef;

    @Builder.Default
    private String version = "v1";

    private String configHash;

    @Builder.Default
    private Boolean enabled = true;

    /**
     * 传输配置 - sse
     */
    private TransportConfigSse transportConfigSse;

    /**
     * 传输配置 - stdio
     */
    private TransportConfigStdio transportConfigStdio;

    /**
     * 传输配置 - streamable http
     */
    private TransportConfigStreamableHttp transportConfigStreamableHttp;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigSse {
        private String baseUri;
        private String sseEndpoint;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigStdio {

        private Map<String, Stdio> stdio;

        @Data
        public static class Stdio {
            private String command;
            private List<String> args;
            private Map<String, String> env;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigStreamableHttp {

        /**
         * MCP 服务基础地址，例如 http://127.0.0.1:8101
         */
        private String baseUri;

        /**
         * Streamable HTTP 端点，默认 /mcp
         */
        @Builder.Default
        private String endpoint = "/mcp";

        /**
         * 透传给服务端的请求头
         */
        @Builder.Default
        private Map<String, String> headers = Map.of();

        /**
         * 是否启用可恢复流
         */
        @Builder.Default
        private Boolean resumableStreams = false;

        /**
         * 是否在启动时主动建立连接
         */
        @Builder.Default
        private Boolean openConnectionOnStartup = true;
    }

}
