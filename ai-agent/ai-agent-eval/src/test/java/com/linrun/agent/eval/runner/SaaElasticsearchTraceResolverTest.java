package com.linrun.agent.eval.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaaElasticsearchTraceResolverTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesTheSingleTraceIdForCanonicalRunId() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loongsuite_traces/_search", exchange -> {
            byte[] body = "{\"hits\":{\"hits\":[{\"_source\":{\"contents\":{\"traceID\":\"trace-123\"}}},{\"_source\":{\"contents\":{\"traceID\":\"trace-123\"}}}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        EvalRunObservation observation = new EvalRunObservation("request", "canonical-run", "", "answer",
                List.of(), List.of(), Set.of(), true, true, true, true, 0, 1L, 0L, "");

        String traceId = new SaaElasticsearchTraceResolver(base(), Duration.ofMillis(1)).resolve(observation);

        assertEquals("trace-123", traceId);
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
