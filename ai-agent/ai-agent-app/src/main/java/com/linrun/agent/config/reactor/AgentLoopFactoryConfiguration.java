package com.linrun.agent.config.reactor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.HookBus;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;

/** Spring extension boundary for the production Agent Loop Harness. */
@Configuration(proxyBeanMethods = false)
public class AgentLoopFactoryConfiguration {

    @Bean
    public AgentLoopFactory agentLoopFactory(ObjectProvider<PermissionPolicy> permissionPolicyProvider,
                                             ObjectProvider<HookBus.Hook> hookProvider,
                                             ObjectProvider<AgentLoopFactory.RunCustomizer> customizerProvider) {
        PermissionPolicy permissionPolicy = permissionPolicyProvider
                .getIfAvailable(DefaultPermissionPolicy::new);
        return new AgentLoopFactory(
                permissionPolicy,
                hookProvider.orderedStream().toList(),
                customizerProvider.orderedStream().toList()
        );
    }
}
