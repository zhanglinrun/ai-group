package com.linrun.agent.infrastructure.tool.durable;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolControlPlane;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolExecutor;
import com.linrun.agent.domain.agent.runtime.tool.durable.DurableToolStore;
import com.linrun.agent.domain.agent.runtime.tool.durable.RemoteDurableToolExecutor;

/** Wires the domain state machine to MySQL and the internal Python worker port. */
@Configuration
public class DurableToolInfrastructureConfiguration {

    @Bean
    public DurableToolControlPlane durableToolControlPlane(DurableToolStore durableToolStore) {
        return new DurableToolControlPlane(durableToolStore);
    }

    @Bean
    public DurableToolExecutor durableToolExecutor(DurableToolControlPlane durableToolControlPlane,
                                                   RemoteHttpPort remoteHttpPort,
                                                   ReactorConfig reactorConfig) {
        return new RemoteDurableToolExecutor(durableToolControlPlane, remoteHttpPort, reactorConfig);
    }
}
