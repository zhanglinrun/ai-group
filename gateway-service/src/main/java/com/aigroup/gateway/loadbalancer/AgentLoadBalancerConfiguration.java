package com.aigroup.gateway.loadbalancer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.net.URI;

/**
 * Child context for {@code agent-service} only. Must not be component-scanned.
 */
public class AgentLoadBalancerConfiguration {

    @Bean
    public ServiceInstanceListSupplier agentServiceInstanceListSupplier(
            ConfigurableApplicationContext context,
            @Value("${gateway.route.agent-fallback-uri:http://agent-service:8090}") String fallbackUri) {
        ServiceInstanceListSupplier delegate = ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .build(context);
        return new AgentFallbackServiceInstanceListSupplier(delegate, URI.create(fallbackUri));
    }
}
