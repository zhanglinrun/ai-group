package com.aigroup.gateway.loadbalancer;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFallbackServiceInstanceListSupplierTest {

    @Test
    void usesFallbackWhenDiscoveryIsEmpty() {
        ServiceInstanceListSupplier delegate = mock(ServiceInstanceListSupplier.class);
        when(delegate.getServiceId()).thenReturn("agent-service");
        when(delegate.get()).thenReturn(Flux.just(List.of()));
        AgentFallbackServiceInstanceListSupplier supplier = new AgentFallbackServiceInstanceListSupplier(
                delegate, URI.create("http://agent-service:8090"));

        List<ServiceInstance> instances = supplier.get().blockFirst();
        assertEquals(1, instances.size());
        assertEquals("agent-service", instances.getFirst().getHost());
        assertEquals(8090, instances.getFirst().getPort());
    }

    @Test
    void prefersDiscoveredInstances() {
        ServiceInstance discovered = new DefaultServiceInstance(
                "agent-1", "agent-service", "10.0.0.8", 8090, false);
        ServiceInstanceListSupplier delegate = mock(ServiceInstanceListSupplier.class);
        when(delegate.getServiceId()).thenReturn("agent-service");
        when(delegate.get()).thenReturn(Flux.just(List.of(discovered)));
        AgentFallbackServiceInstanceListSupplier supplier = new AgentFallbackServiceInstanceListSupplier(
                delegate, URI.create("http://agent-service:8090"));

        List<ServiceInstance> instances = supplier.get().blockFirst();
        assertEquals("10.0.0.8", instances.getFirst().getHost());
    }
}
