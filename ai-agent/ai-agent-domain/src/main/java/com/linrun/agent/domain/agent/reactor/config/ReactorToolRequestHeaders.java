package com.linrun.agent.domain.agent.reactor.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * runtime/tools 内部请求头构造器。
 * 令牌只存在于服务端配置和服务间请求中，不下发给浏览器业务代码。
 */
public final class ReactorToolRequestHeaders {

    public static final String TOKEN_HEADER = "X-Tool-Token";

    private ReactorToolRequestHeaders() {
    }

    public static Map<String, String> json(ReactorConfig config) {
        return withToken(config == null ? null : config.getReactorToolToken(),
                Map.of("Content-Type", "application/json"));
    }

    public static Map<String, String> json(String token) {
        return withToken(token, Map.of("Content-Type", "application/json"));
    }

    public static Map<String, String> sse(ReactorConfig config) {
        return withToken(config == null ? null : config.getReactorToolToken(), Map.of(
                "Accept", "text/event-stream",
                "Cache-Control", "no-cache",
                "Content-Type", "application/json"
        ));
    }

    public static Map<String, String> sse(String token) {
        return withToken(token, Map.of(
                "Accept", "text/event-stream",
                "Cache-Control", "no-cache",
                "Content-Type", "application/json"
        ));
    }

    public static Map<String, String> withToken(String token, Map<String, String> baseHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (baseHeaders != null) {
            headers.putAll(baseHeaders);
        }
        if (token != null && !token.isBlank()) {
            headers.put(TOKEN_HEADER, token.trim());
        }
        return Map.copyOf(headers);
    }
}
