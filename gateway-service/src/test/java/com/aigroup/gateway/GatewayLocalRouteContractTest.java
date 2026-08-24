package com.aigroup.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "ai-group.internal.token=test-internal-token-for-local-regression-012345678901234567890"
})
@ActiveProfiles("local")
class GatewayLocalRouteContractTest {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RouteLocator routeLocator;


    @Test
    void javaRoutesTargetLocalServicePorts() {
        assertEquals(URI.create("http://127.0.0.1:8081"), uri("auth"));
        assertEquals(URI.create("http://127.0.0.1:18082"), uri("member"));
        assertEquals(URI.create("http://127.0.0.1:8070"), uri("pay"));
        assertEquals(URI.create("http://127.0.0.1:8091"), uri("group"));
    }

    private URI uri(String routeId) {
        return routeLocator.getRoutes()
                .filter(route -> routeId.equals(route.getId()))
                .map(Route::getUri)
                .blockFirst();
    }

    @Test
    void agentRoutesTargetLocalAgentPort() {
        URI sseUri = routeLocator.getRoutes()
                .filter(route -> "agent-sse".equals(route.getId()))
                .map(Route::getUri)
                .blockFirst();
        URI jsonUri = routeLocator.getRoutes()
                .filter(route -> "agent-json".equals(route.getId()))
                .map(Route::getUri)
                .blockFirst();

        assertEquals(URI.create("http://127.0.0.1:8090"), sseUri);
        assertEquals(URI.create("http://127.0.0.1:8090"), jsonUri);
    }
}
