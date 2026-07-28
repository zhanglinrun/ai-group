package com.linrun.agent.test.domain.dataagent;

import com.linrun.agent.config.reactor.DataAgentInitRunner;
import com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig;
import com.linrun.agent.domain.agent.reactor.config.data.EsConfig;
import com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService;
import com.linrun.agent.domain.agent.reactor.service.ColumnValueSyncService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class DataAgentInitRunnerRefreshTest {

    @Test
    public void shouldRefreshPostgresSchemaAndEsWhenForced() throws Exception {
        Fixture fixture = fixture(true, true);

        fixture.runner.run();

        Mockito.verify(fixture.columnValueSyncService).recreateColumnValueIndex();
        Mockito.verify(fixture.chatModelInfoService).refreshModelInfo(fixture.config);
        Mockito.verify(fixture.chatModelInfoService, Mockito.never()).initModelInfo(fixture.config);
    }

    @Test
    public void shouldDisableOptionalEsButContinueRegularStartup() throws Exception {
        Fixture fixture = fixture(false, true);
        Mockito.doThrow(new IllegalStateException("es unavailable"))
                .when(fixture.columnValueSyncService).initColumnValueIndex();

        fixture.runner.run();

        Assert.assertFalse(fixture.config.getEsConfig().getEnable());
        Mockito.verify(fixture.chatModelInfoService).initModelInfo(fixture.config);
    }

    @Test
    public void shouldFailForcedRefreshWhenEsRefreshFails() {
        Fixture fixture = fixture(true, true);
        Mockito.doThrow(new IllegalStateException("es unavailable"))
                .when(fixture.columnValueSyncService).recreateColumnValueIndex();

        Assert.assertThrows(IllegalStateException.class, fixture.runner::run);
        Mockito.verifyNoInteractions(fixture.chatModelInfoService);
    }

    private Fixture fixture(boolean forceRefresh, boolean esEnabled) {
        DataAgentConfig config = new DataAgentConfig();
        config.setForceRefresh(forceRefresh);
        EsConfig esConfig = new EsConfig();
        esConfig.setEnable(esEnabled);
        config.setEsConfig(esConfig);
        ChatModelInfoService chatModelInfoService = Mockito.mock(ChatModelInfoService.class);
        ColumnValueSyncService columnValueSyncService = Mockito.mock(ColumnValueSyncService.class);
        DataAgentInitRunner runner = new DataAgentInitRunner();
        ReflectionTestUtils.setField(runner, "dataAgentConfig", config);
        ReflectionTestUtils.setField(runner, "chatModelInfoService", chatModelInfoService);
        ReflectionTestUtils.setField(runner, "columnValueSyncService", columnValueSyncService);
        return new Fixture(runner, config, chatModelInfoService, columnValueSyncService);
    }

    private record Fixture(DataAgentInitRunner runner,
                           DataAgentConfig config,
                           ChatModelInfoService chatModelInfoService,
                           ColumnValueSyncService columnValueSyncService) {
    }
}
