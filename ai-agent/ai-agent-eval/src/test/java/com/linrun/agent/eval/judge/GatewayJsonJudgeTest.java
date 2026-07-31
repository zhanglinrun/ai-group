package com.linrun.agent.eval.judge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayJsonJudgeTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsOnlyStrictJsonAndFallsBackWhenFieldsAreMissing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/judge", exchange -> {
            byte[] body = "{\"verdict\":\"FAIL\",\"rationale\":\"citation missing\",\"model\":\"judge-a\",\"version\":\"1\",\"promptHash\":\"sha256:abc\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        GatewayJsonJudge judge = new GatewayJsonJudge(endpoint(), "token", Duration.ofSeconds(5));

        JudgeOutcome outcome = judge.judge(new JudgeRequest("case", java.util.List.of("citation"), "answer", java.util.List.of(), java.util.List.of()));

        assertEquals(JudgeOutcome.Status.AVAILABLE, outcome.status());
        assertEquals("FAIL", outcome.verdict());
        server.stop(0);
        server = null;

        JudgeOutcome unavailable = judge.judge(new JudgeRequest("case", java.util.List.of(), "answer", java.util.List.of(), java.util.List.of()));
        assertEquals(JudgeOutcome.Status.UNAVAILABLE, unavailable.status());
        assertEquals("NEEDS_HUMAN_REVIEW", unavailable.verdict());
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/judge");
    }
}
