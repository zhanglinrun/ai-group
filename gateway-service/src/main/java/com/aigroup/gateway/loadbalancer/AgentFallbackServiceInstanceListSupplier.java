package com.aigroup.gateway.loadbalancer;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

/**
 * Uses Nacos instances when present; otherwise a static Docker/DNS URI so Agent
 * fail-open registration does not black-hole Gateway routes.
 */
public class AgentFallbackServiceInstanceListSupplier implements ServiceInstanceListSupplier {

    private final ServiceInstanceListSupplier delegate;
    private final ServiceInstance fallback;

    public AgentFallbackServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, URI fallbackUri) {
        this.delegate = delegate;
        this.fallback = toInstance(delegate.getServiceId(), fallbackUri);
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(this::orFallback);
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        return delegate.get(request).map(this::orFallback);
    }

    List<ServiceInstance> orFallback(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return List.of(fallback);
        }
        return instances;
    }

    static ServiceInstance toInstance(String serviceId, URI fallbackUri) {
        int port = fallbackUri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(fallbackUri.getScheme()) ? 443 : 80;
        }
        return new DefaultServiceInstance(
                serviceId + "-fallback",
                serviceId,
                fallbackUri.getHost(),
                port,
                "https".equalsIgnoreCase(fallbackUri.getScheme()));
    }
}
