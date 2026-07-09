package com.aigroup.gateway;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.gateway.filter.AuthGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = "spring.cloud.nacos.discovery.enabled=false")
class GatewayApplicationContextTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private InternalTokenProperties internalTokenProperties;

    @Autowired
    private AuthGlobalFilter authGlobalFilter;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoadsInternalTokenProperties() {
        assertNotNull(internalTokenProperties);
        assertEquals("ai-group-dev-internal-token-change-in-prod", internalTokenProperties.getToken());
        assertNotNull(authGlobalFilter);
    }

    @Test
    void agentRoutesDedupeCorsResponseHeaders() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertRouteHasDedupe(routes, "agent-api");
        assertRouteHasDedupe(routes, "agent-web");
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
