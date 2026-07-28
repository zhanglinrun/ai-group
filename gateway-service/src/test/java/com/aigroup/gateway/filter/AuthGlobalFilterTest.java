package com.aigroup.gateway.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthGlobalFilterTest {

    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    @Mock
    private Claims claims;

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
    void activePayNotify_withOrdinaryJwtButNoInternalToken_isForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/alipay/active_pay_notify?outTradeNo=order-1")
                .header("Authorization", "Bearer valid-user-token")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void userApi_withRefreshToken_isUnauthorized() {
        when(jwtUtils.parseAccessToken("refresh-token")).thenThrow(new IllegalArgumentException("not access"));

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
        when(jwtUtils.parseAccessToken("valid-token")).thenReturn(claims);
        when(jwtUtils.blacklistKey("valid-token")).thenReturn("digest");
        when(reactiveStringRedisTemplate.hasKey("jwt:blacklist:digest")).thenReturn(Mono.just(false));
        when(claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class)).thenReturn(42L);
        when(claims.get(CommonConstant.TOKEN_CLAIM_USERNAME, String.class)).thenReturn("smoke_user");
        when(claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class)).thenReturn("USER");

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
            assertEquals("secret-internal-token",
                    ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_TOKEN));
            return Mono.empty();
        })).verifyComplete();

        assertNull(exchange.getResponse().getStatusCode());
    }
}
