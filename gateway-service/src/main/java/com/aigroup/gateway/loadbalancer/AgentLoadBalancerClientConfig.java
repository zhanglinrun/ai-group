package com.aigroup.gateway.loadbalancer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.cloud.nacos.discovery.enabled", havingValue = "true", matchIfMissing = true)
@LoadBalancerClient(name = "agent-service", configuration = AgentLoadBalancerConfiguration.class)
public class AgentLoadBalancerClientConfig {
}
