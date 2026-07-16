package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.config.ReactorToolRequestHeaders;
import org.wwz.ai.domain.agent.reactor.config.data.DataAgentConfig;
import org.wwz.ai.domain.agent.reactor.config.data.QdrantConfig;
import org.wwz.ai.domain.agent.reactor.service.EmbeddingService;
import org.wwz.ai.domain.agent.runtime.dto.skill.ScriptRunnerToolRequest;
import org.wwz.ai.domain.agent.runtime.dto.skill.ScriptRunnerToolResponse;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillScriptRunnerClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ReactorToolSecurityTest {

    @Test
    public void shouldBuildAuthenticatedJsonAndSseHeaders() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactorToolToken", " shared-token ");

        Map<String, String> jsonHeaders = ReactorToolRequestHeaders.json(config);
        Map<String, String> sseHeaders = ReactorToolRequestHeaders.sse(config);

        Assert.assertEquals("application/json", jsonHeaders.get("Content-Type"));
        Assert.assertEquals("shared-token", jsonHeaders.get("X-Tool-Token"));
        Assert.assertEquals("text/event-stream", sseHeaders.get("Accept"));
        Assert.assertEquals("shared-token", sseHeaders.get("X-Tool-Token"));
    }

    @Test
    public void scriptRunnerClientShouldPropagateToolToken() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://127.0.0.1:1601/");
        ReflectionTestUtils.setField(config, "reactorToolToken", "tool-token");
        AtomicReference<RemoteHttpRequest> capturedRequest = new AtomicReference<>();
        RemoteHttpPort remoteHttpPort = request -> {
            capturedRequest.set(request);
            return "{\"requestId\":\"req-1\",\"skillName\":\"demo\",\"scriptName\":\"run\","
                    + "\"runtime\":\"python\",\"success\":true,\"exitCode\":0,\"fileInfo\":[]}";
        };
        SkillScriptRunnerClient client = new SkillScriptRunnerClient(config, remoteHttpPort);

        ScriptRunnerToolResponse response = client.run(ScriptRunnerToolRequest.builder()
                .requestId("req-1")
                .skillName("demo")
                .scriptName("run")
                .scriptPath("scripts/run.py")
                .runtime("python")
                .timeoutSeconds(5)
                .build());

        Assert.assertTrue(Boolean.TRUE.equals(response.getSuccess()));
        Assert.assertNotNull(capturedRequest.get());
        Assert.assertEquals("http://127.0.0.1:1601/v1/tool/script_runner", capturedRequest.get().getUrl());
        Assert.assertEquals("tool-token", capturedRequest.get().getHeaders().get("X-Tool-Token"));
    }

    @Test
    public void shouldNotEmitEmptyTokenHeaderInExplicitLocalMode() {
        Assert.assertFalse(ReactorToolRequestHeaders.json(" ").containsKey("X-Tool-Token"));
    }

    @Test
    public void embeddingOverrideShouldNotReceiveInternalToolToken() {
        DataAgentConfig dataAgentConfig = new DataAgentConfig();
        dataAgentConfig.setAgentUrl("http://127.0.0.1:1601");
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactorToolToken", "internal-token");
        QdrantConfig qdrantConfig = new QdrantConfig();
        qdrantConfig.setEmbeddingUrl("https://embedding.example/v1/embeddings");
        dataAgentConfig.setQdrantConfig(qdrantConfig);
        AtomicReference<RemoteHttpRequest> capturedRequest = new AtomicReference<>();
        RemoteHttpPort remoteHttpPort = request -> {
            capturedRequest.set(request);
            return "[[0.1,0.2]]";
        };
        EmbeddingService embeddingService = new EmbeddingService();
        ReflectionTestUtils.setField(embeddingService, "dataAgentConfig", dataAgentConfig);
        ReflectionTestUtils.setField(embeddingService, "remoteHttpPort", remoteHttpPort);
        ReflectionTestUtils.setField(embeddingService, "reactorConfig", config);

        Assert.assertNotNull(embeddingService.getVector("hello"));
        Assert.assertFalse(capturedRequest.get().getHeaders().containsKey("X-Tool-Token"));
    }
}
