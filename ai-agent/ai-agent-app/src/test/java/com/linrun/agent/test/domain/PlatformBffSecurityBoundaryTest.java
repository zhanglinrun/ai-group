package com.linrun.agent.test.domain;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.linrun.agent.infrastructure.gateway.platform.PlatformBffClient;
import com.linrun.agent.infrastructure.gateway.platform.PlatformBffFeignConfiguration;

import java.lang.reflect.Method;
import java.util.Arrays;

public class PlatformBffSecurityBoundaryTest {

    @Test
    public void clientMustDeclareOnlyReadEndpointsAndTrustedUserHeader() {
        Method[] methods = PlatformBffClient.class.getDeclaredMethods();
        Assert.assertEquals(4, methods.length);
        for (Method method : methods) {
            Assert.assertNotNull(method.getAnnotation(GetMapping.class));
            Assert.assertTrue(method.getName(), hasUserIdHeader(method));
        }
    }

    @Test
    public void clientInterceptorMustAttachInternalTrustHeaders() {
        PlatformBffFeignConfiguration configuration = new PlatformBffFeignConfiguration();
        RequestInterceptor interceptor = configuration.platformBffIdentityInterceptor("internal-test-token");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        Assert.assertEquals("internal-test-token",
                template.headers().get("X-Internal-Token").iterator().next());
        Assert.assertEquals("true",
                template.headers().get("X-Gateway-Request").iterator().next());
        Assert.assertFalse(template.headers().containsKey("X-User-Id"));
    }

    private boolean hasUserIdHeader(Method method) {
        return Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(annotation -> annotation instanceof RequestHeader)
                .map(annotation -> (RequestHeader) annotation)
                .anyMatch(header -> PlatformBffClient.HEADER_USER_ID.equals(header.value()));
    }
}
