package com.linrun.agent.eval.runner;

import com.linrun.agent.eval.dataset.EvalCase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySseRunnerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesCanonicalSseAndCapturesReplayIdentifiers() throws Exception {
        AtomicBoolean authorized = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eval", exchange -> {
            authorized.set("Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
            String events = "event: tool_end\n"
                    + "data: {\"type\":\"tool_end\",\"runId\":\"run-7\",\"traceId\":\"trace-7\",\"toolName\":\"analyze_file\",\"status\":\"success\",\"arguments\":{\"artifactId\":\"fixture.pdf\"}}\n\n"
                    + "event: text\n"
                    + "data: {\"type\":\"text\",\"requestId\":\"server-request\",\"delta\":\"PDF 摘要 https://example.test/source\"}\n\n"
                    + "event: complete\n"
                    + "data: {\"type\":\"complete\"}\n\n";
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        EvalCase evalCase = EvalCase.from(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"id\":\"sse\",\"input\":\"x\",\"mode\":\"STANDARD\"}"));
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/eval");

        EvalRunObservation observation = new GatewaySseRunner(endpoint, "test-token", Duration.ofSeconds(5)).run(evalCase, 1);

        assertTrue(authorized.get());
        assertTrue(observation.completed());
        assertEquals("server-request", observation.requestId());
        assertEquals("run-7", observation.runId());
        assertEquals("trace-7", observation.traceId());
        assertEquals("fixture.pdf", observation.toolParameters().get("analyze_file").get("artifactId"));
        assertTrue(observation.citations().contains("https://example.test/source"));
    }
}
