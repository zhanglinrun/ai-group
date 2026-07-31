package com.linrun.agent.test.domain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.infrastructure.adapter.port.ReactorToolFileArtifactAdapter;
import com.linrun.agent.infrastructure.gateway.ReactorFileGateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ReactorToolHeaderPropagationTest {

    @Test
    public void fileArtifactAdapterShouldPropagateTokenOnlyForRuntimeToolRequests() throws Exception {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "reactorToolToken", "file-token");
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://127.0.0.1:1601");
        List<RemoteHttpRequest> requests = new ArrayList<>();
        RemoteHttpPort remoteHttpPort = request -> {
            requests.add(request);
            return "{\"fileName\":\"demo.txt\",\"fileSize\":4}";
        };
        ReactorToolFileArtifactAdapter adapter = new ReactorToolFileArtifactAdapter(remoteHttpPort);
        ReflectionTestUtils.setField(adapter, "reactorConfig", config);

        adapter.upload("http://127.0.0.1:1601", null);
        adapter.get("http://127.0.0.1:1601", null);
        adapter.readText("http://127.0.0.1:1601/v1/file_tool/download/session/demo.txt", 60L);
        adapter.readText("https://external.example/download/demo.txt", 60L);

        Assert.assertEquals(4, requests.size());
        Assert.assertEquals("file-token", requests.get(0).getHeaders().get("X-Tool-Token"));
        Assert.assertEquals("file-token", requests.get(1).getHeaders().get("X-Tool-Token"));
        Assert.assertEquals("file-token", requests.get(2).getHeaders().get("X-Tool-Token"));
        Assert.assertNull(requests.get(3).getHeaders().get("X-Tool-Token"));
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
