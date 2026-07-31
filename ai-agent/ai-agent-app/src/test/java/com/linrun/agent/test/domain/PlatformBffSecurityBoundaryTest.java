package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.linrun.agent.infrastructure.gateway.platform.PlatformBffClient;

import java.lang.reflect.Method;
import java.util.Arrays;

public class PlatformBffSecurityBoundaryTest {

    @Test
    public void clientMustDeclareOnlyReadEndpointsAndOwnerHeader() {
        Method[] methods = PlatformBffClient.class.getDeclaredMethods();
        Assert.assertEquals(4, methods.length);
        for (Method method : methods) {
            Assert.assertNotNull(method.getAnnotation(GetMapping.class));
            Assert.assertTrue(method.getName(), hasHeader(method, PlatformBffClient.HEADER_USER_ID));
        }
    }

    private boolean hasHeader(Method method, String expectedName) {
        return Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(annotation -> annotation instanceof RequestHeader)
                .map(annotation -> (RequestHeader) annotation)
                .anyMatch(header -> expectedName.equals(header.value()));
    }
}
