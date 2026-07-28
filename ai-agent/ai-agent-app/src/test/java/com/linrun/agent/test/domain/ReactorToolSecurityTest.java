package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.runtime.dto.skill.ScriptRunnerToolRequest;
import com.linrun.agent.domain.agent.runtime.dto.skill.ScriptRunnerToolResponse;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptRunnerClient;

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

}
