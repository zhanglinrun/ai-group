package org.wwz.ai.test.domain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.mcp.McpTool;
import org.wwz.ai.infrastructure.adapter.port.ReactorToolFileArtifactAdapter;
import org.wwz.ai.infrastructure.gateway.ReactorFileGateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ReactorToolHeaderPropagationTest {

    @Test
    public void mcpToolShouldPropagateTokenToConfiguredReactorClient() {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "mcpClientUrl", "http://127.0.0.1:1601");
        ReflectionTestUtils.setField(config, "reactorToolToken", "mcp-token");
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = request -> {
            requests.add(request);
            return "{}";
        };
        AgentContext context = AgentContext.builder()
                .requestId("req-mcp-auth")
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(config)
                        .remoteHttpPort(remoteHttpPort)
                        .build())
                .build();
        McpTool tool = new McpTool();
        tool.setAgentContext(context);

        tool.listTool("http://mcp.example");
        tool.callTool("http://mcp.example", "echo", java.util.Map.of("value", "ok"));

        Assert.assertEquals(2, requests.size());
        for (RemoteHttpRequest request : requests) {
            Assert.assertEquals("mcp-token", request.getHeaders().get("X-Tool-Token"));
        }
    }

    @Test
    public void jsonFileArtifactAdapterShouldPropagateTokenForMutations() throws Exception {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactorToolToken", "file-token");
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = request -> {
            requests.add(request);
            return "{\"fileName\":\"demo.txt\",\"fileSize\":4}";
        };
        ReactorToolFileArtifactAdapter adapter = new ReactorToolFileArtifactAdapter(remoteHttpPort);
        ReflectionTestUtils.setField(adapter, "reactorConfig", config);

        adapter.upload("http://127.0.0.1:1601", null);
        adapter.get("http://127.0.0.1:1601", null);

        Assert.assertEquals(2, requests.size());
        Assert.assertEquals("file-token", requests.get(0).getHeaders().get("X-Tool-Token"));
        Assert.assertEquals("file-token", requests.get(1).getHeaders().get("X-Tool-Token"));
    }

    @Test
    public void multipartFileGatewayShouldPropagateTokenWithoutExposingItToBrowser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedToken = new AtomicReference<>();
        server.createContext("/v1/file_tool/upload_file_data", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Tool-Token"));
            drain(exchange);
            byte[] body = "{\"fileName\":\"demo.txt\",\"fileSize\":4,\"domainUrl\":\"http://127.0.0.1/preview\",\"downloadUrl\":\"http://127.0.0.1/download\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ReactorConfig config = new ReactorConfig();
            ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(config, "reactorToolToken", "multipart-token");
            ReactorFileGateway gateway = new ReactorFileGateway();
            ReflectionTestUtils.setField(gateway, "reactorConfig", config);

            gateway.uploadConversationFile(
                    "session-1",
                    new MockMultipartFile("file", "demo.txt", "text/plain", "demo".getBytes(StandardCharsets.UTF_8))
            );

            Assert.assertEquals("multipart-token", receivedToken.get());
        } finally {
            server.stop(0);
        }
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            input.transferTo(OutputStream.nullOutputStream());
        }
    }
}
