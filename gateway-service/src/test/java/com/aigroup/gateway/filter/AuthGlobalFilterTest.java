package com.aigroup.gateway.filter;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.constant.CommonConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class AuthGlobalFilterTest {

    private InternalTokenProperties internalTokenProperties;
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        internalTokenProperties = new InternalTokenProperties();
        internalTokenProperties.setToken("secret-internal-token");
        filter = new AuthGlobalFilter(internalTokenProperties);
    }

    @Test
    void retiredGroupNotify_withoutSession_isUnauthorized() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/alipay/group_buy_notify").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void retiredActivePayNotify_withoutSession_isUnauthorized() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/alipay/active_pay_notify").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void retiredGatewayReconcilePath_withoutSession_isUnauthorized() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/pay/orders/reconcile/order-1").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void actuator_withoutInternalToken_isForbidden() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void actuator_withInternalToken_isAllowedAfterStrippingBrowserIdentity() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/actuator/prometheus")
                .header(CommonConstant.HEADER_INTERNAL_TOKEN, "secret-internal-token").build());

        StepVerifier.create(filter.filter(exchange, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
            return Mono.empty();
        })).verifyComplete();
    }

    @Test
    void userApi_withoutSession_isUnauthorized() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/bff/orders").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void userApi_withUnknownBearer_isUnauthorized() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/bff/account/summary")
                .header("Authorization", "Bearer not-a-real-session")
                .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void whitelist_stripsForgedInternalJwt() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/login")
                .header(CommonConstant.HEADER_INTERNAL_JWT, "forged.jwt.token")
                .header(CommonConstant.HEADER_USER_ID, "999")
                .build());

        StepVerifier.create(filter.filter(exchange, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_INTERNAL_JWT));
            assertNull(ex.getRequest().getHeaders().getFirst(CommonConstant.HEADER_USER_ID));
            return Mono.empty();
        })).verifyComplete();
    }
}
