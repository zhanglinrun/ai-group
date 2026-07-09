package com.aigroup.gateway.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthGlobalFilterTest {

    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private InternalTokenProperties internalTokenProperties;
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        internalTokenProperties = new InternalTokenProperties();
        internalTokenProperties.setToken("secret-internal-token");
        filter = new AuthGlobalFilter(jwtUtils, reactiveStringRedisTemplate, internalTokenProperties);
    }

    @Test
    void groupBuyNotify_withoutInternalToken_isForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/alipay/group_buy_notify")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void groupBuyNotify_withValidInternalToken_forwardsToken() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/alipay/group_buy_notify")
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, "secret-internal-token")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> {
            assertEquals("secret-internal-token",
                    ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_TOKEN));
            return Mono.empty();
        })).verifyComplete();
    }

    @Test
    void userApi_withRefreshToken_isUnauthorized() {
        when(jwtUtils.validateToken("refresh-token")).thenReturn(true);
        when(jwtUtils.getTokenType("refresh-token")).thenReturn(CommonConstant.TOKEN_TYPE_REFRESH);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/bff/account/summary")
                .header("Authorization", "Bearer refresh-token")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void userApi_withoutJwt_isUnauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/bff/orders")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void userApi_withValidJwt_injectsGatewayIdentityHeaders() {
        when(jwtUtils.validateToken("valid-token")).thenReturn(true);
        when(jwtUtils.getTokenType("valid-token")).thenReturn(CommonConstant.TOKEN_TYPE_ACCESS);
        when(reactiveStringRedisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
        when(jwtUtils.getUserId("valid-token")).thenReturn(42L);
        when(jwtUtils.getUsername("valid-token")).thenReturn("smoke_user");
        when(jwtUtils.getRole("valid-token")).thenReturn("USER");

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/bff/account/summary")
                .header("Authorization", "Bearer valid-token")
                .header(CommonConstant.HEADER_USER_ID, "999")
                .header(CommonConstant.HEADER_GATEWAY_REQUEST, "true")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> {
            assertEquals("42", ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
            assertEquals("smoke_user", ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_USERNAME));
            assertEquals("USER", ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_ROLE));
            assertEquals("true", ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_GATEWAY_REQUEST));
            return Mono.empty();
        })).verifyComplete();

        assertNull(exchange.getResponse().getStatusCode());
    }
}
