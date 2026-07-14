package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.rag.SopRecallService;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.runtime.dto.SopRecallResponse;

public class SopRecallServiceTest {

    @Test
    public void shouldFailOpenWithoutConfiguredKnowledgeUrl() throws Exception {
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        SopRecallService service = newService("", remoteHttpPort);

        Assert.assertNull(service.sopRecall("req-sop-empty", "测试问题"));
        Mockito.verifyNoInteractions(remoteHttpPort);
    }

    @Test
    public void shouldBuildAbsoluteRecallUrlWithoutDuplicateSlash() throws Exception {
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        Mockito.when(remoteHttpPort.execute(Mockito.any())).thenReturn(
                "{\"code\":200,\"data\":{\"sop_mode\":\"none\",\"choosed_sop_string\":\"步骤\"}}");
        SopRecallService service = newService("http://127.0.0.1:1601/", remoteHttpPort);

        SopRecallResponse response = service.sopRecall("req-sop-url", "测试问题");

        Assert.assertNotNull(response);
        ArgumentCaptor<RemoteHttpRequest> request = ArgumentCaptor.forClass(RemoteHttpRequest.class);
        Mockito.verify(remoteHttpPort).execute(request.capture());
        Assert.assertEquals("http://127.0.0.1:1601/v1/tool/sopRecall", request.getValue().getUrl());
    }

    private SopRecallService newService(String knowledgeUrl, RemoteHttpPort remoteHttpPort) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "autoBotsKnowledgeUrl", knowledgeUrl);
        SopRecallService service = new SopRecallService();
        ReflectionTestUtils.setField(service, "reactorConfig", config);
        ReflectionTestUtils.setField(service, "remoteHttpPort", remoteHttpPort);
        return service;
    }
}
