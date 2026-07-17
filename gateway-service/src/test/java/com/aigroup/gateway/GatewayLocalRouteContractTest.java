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

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
@ActiveProfiles("local")
class GatewayLocalRouteContractTest {

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void memberRouteTargetsMemberServiceDefaultLocalPort() {
        URI memberUri = routeLocator.getRoutes()
                .filter(route -> "member".equals(route.getId()))
                .map(Route::getUri)
                .blockFirst();

        assertEquals(URI.create("http://127.0.0.1:18082"), memberUri);
    }
}
