package com.aigroup.bff.agent;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentProxyControllerAuthTest {

    @AfterEach
    void clear() {
        RequestUserContext.clear();
    }

    @Test
    void eventsRejectsDirectCallWithoutVerifiedIdentity() {
        AgentProxyController controller = controller();
        assertThrows(BusinessException.class,
                () -> controller.events("run_1", new MockHttpServletRequest()));
    }

    @Test
    void jsonPassthroughRejectsDirectCallWithoutVerifiedIdentity() {
        AgentProxyController controller = controller();
        assertThrows(BusinessException.class,
                () -> controller.listRuns(null, 20, 0, new MockHttpServletRequest()));
    }

    private static AgentProxyController controller() {
        ObjectProvider<WebClient.Builder> unused = unusedProvider();
        return new AgentProxyController(
                WebClient.builder(),
                unused,
                providerOf(WebClient.builder()),
                unused,
                "http://agent-service");
    }

    private static ObjectProvider<WebClient.Builder> providerOf(WebClient.Builder builder) {
        return new ObjectProvider<>() {
            @Override
            public WebClient.Builder getObject(Object... args) throws BeansException {
                return builder;
            }

            @Override
            public WebClient.Builder getObject() throws BeansException {
                return builder;
            }

            @Override
            public WebClient.Builder getIfAvailable() {
                return builder;
            }

            @Override
            public WebClient.Builder getIfUnique() {
                return builder;
            }
        };
    }

    private static ObjectProvider<WebClient.Builder> unusedProvider() {
        return providerOf(WebClient.builder());
    }
}
