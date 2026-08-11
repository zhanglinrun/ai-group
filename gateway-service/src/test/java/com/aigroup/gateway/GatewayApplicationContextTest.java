package com.aigroup.gateway;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.gateway.filter.AuthGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.profiles.active=local",
        "spring.cloud.nacos.discovery.enabled=false",
        "ai-group.internal.token=test-internal-token-for-local-regression-012345678901234567890"
})
class GatewayApplicationContextTest {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private InternalTokenProperties internalTokenProperties;

    @Autowired
    private AuthGlobalFilter authGlobalFilter;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsInternalTokenProperties() {
        assertNotNull(internalTokenProperties);
        assertEquals("test-internal-token-for-local-regression-012345678901234567890", internalTokenProperties.getToken());
        assertNotNull(authGlobalFilter);
    }

    @Test
    void bffRoutesDedupeCorsResponseHeaders() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertRouteHasDedupe(routes, "bff");
    }

    @Test
    void bffAgentRouteIncludesRunApi() {
        Route route = routeLocator.getRoutes()
                .filter(item -> "bff".equals(item.getId()))
                .blockFirst();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/agent/runs/run-1").build());

        assertNotNull(route);
        assertTrue(Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block()));
    }

    @Test
    void bffSseRouteMatchesReplayEndpointAndDeepRunTimeoutIsFiveMinutes() {
        Route route = routeLocator.getRoutes()
                .filter(item -> "bff".equals(item.getId()))
                .blockFirst();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/agent/runs/run-1/events")
                        .header("Last-Event-ID", "42").build());

        assertNotNull(route);
        assertTrue(Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block()));
        assertEquals("42", exchange.getRequest().getHeaders().getFirst("Last-Event-ID"));
        assertTrue(environment.getProperty(
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout", Long.class, 0L) >= 300_000L);
    }

    private void assertRouteHasDedupe(List<Route> routes, String routeId) {
        Route route = routes.stream()
                .filter(item -> routeId.equals(item.getId()))
                .findFirst()
                .orElseThrow();
        boolean hasDedupe = route.getFilters().stream()
                .map(Object::toString)
                .anyMatch(text -> text.contains("DedupeResponseHeader"));
        assertTrue(hasDedupe, routeId + " should dedupe downstream CORS response headers");
    }
}
