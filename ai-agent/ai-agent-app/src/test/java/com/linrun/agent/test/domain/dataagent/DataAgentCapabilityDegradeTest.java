package com.linrun.agent.test.domain.dataagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.config.reactor.DataAgentInitRunner;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.rag.DataAgentQueryService;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig;
import com.linrun.agent.domain.agent.reactor.config.data.EsConfig;
import com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService;
import com.linrun.agent.domain.agent.reactor.service.ColumnValueSyncService;
import com.linrun.agent.infrastructure.adapter.port.OkHttpRemoteHttpAdapter;
import com.linrun.agent.infrastructure.adapter.port.OkHttpRemoteStreamAdapter;
import com.linrun.agent.infrastructure.adapter.port.ReactorToolFileArtifactAdapter;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;
import com.linrun.agent.trigger.http.dataagent.DataAgentController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 能力降级测试。
 */
public class DataAgentCapabilityDegradeTest {

    @Test
    public void shouldInjectStableDomainSeamIntoDataAgentController() {
        List<String> fieldTypes = Arrays.stream(DataAgentController.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(Collectors.toList());

        Assert.assertTrue(fieldTypes.contains(DataAgentQueryService.class.getName()));
        Assert.assertFalse(fieldTypes.contains("com.linrun.agent.domain.agent.reactor.service.DataAgentService"));
    }

    @Test
    public void shouldAssembleInfrastructureOwnedRuntimeAdapters() {
        ReactorRuntimeDependencies dependencies = ReactorRuntimeTestSupport.runtimeDependencies(new ReactorConfig());

        Assert.assertTrue(dependencies.requireRemoteHttpPort() instanceof OkHttpRemoteHttpAdapter);
        Assert.assertTrue(dependencies.requireRemoteStreamPort() instanceof OkHttpRemoteStreamAdapter);
        Assert.assertTrue(dependencies.requireFileArtifactPort() instanceof ReactorToolFileArtifactAdapter);
    }

    @Test
    public void shouldDisableEsWhenRegularStartupInitFails() throws Exception {
        DataAgentInitRunner runner = new DataAgentInitRunner();
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        EsConfig esConfig = new EsConfig();
        esConfig.setEnable(true);
        dataAgentConfig.setEsConfig(esConfig);
        dataAgentConfig.setForceRefresh(false);

        ColumnValueSyncService columnValueSyncService = Mockito.mock(ColumnValueSyncService.class);
        Mockito.doThrow(new IllegalStateException("es init failed")).when(columnValueSyncService).initColumnValueIndex();

        ReflectionTestUtils.setField(runner, "dataAgentConfig", dataAgentConfig);
        ReflectionTestUtils.setField(runner, "chatModelInfoService", Mockito.mock(ChatModelInfoService.class));
        ReflectionTestUtils.setField(runner, "columnValueSyncService", columnValueSyncService);

        runner.run();

        Assert.assertFalse(dataAgentConfig.getEsConfig().getEnable());
    }
}
