package com.linrun.agent.eval.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read-only SAA trace correlation by the canonical Gateway run id; it never sends prompts or output text. */
public final class SaaElasticsearchTraceResolver implements TraceIdResolver {
    private final HttpClient client;
    private final URI searchEndpoint;
    private final Duration wait;
    private final ObjectMapper json = new ObjectMapper();

    public SaaElasticsearchTraceResolver(URI elasticsearchBaseUrl, Duration wait) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), searchEndpoint(elasticsearchBaseUrl), wait);
    }

    SaaElasticsearchTraceResolver(HttpClient client, URI searchEndpoint, Duration wait) {
        this.client = client;
        this.searchEndpoint = searchEndpoint;
        this.wait = wait == null ? Duration.ofSeconds(5) : wait;
    }

    @Override
    public String resolve(EvalRunObservation observation) throws Exception {
        if (observation.runId().isBlank()) {
            return "";
        }
        long deadline = System.nanoTime() + wait.toNanos();
        do {
            String traceId = lookup(observation.runId());
            if (!traceId.isBlank()) {
                return traceId;
            }
            if (System.nanoTime() < deadline) {
                Thread.sleep(Math.min(250L, Math.max(1L, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
            }
        } while (System.nanoTime() < deadline);
        return "";
    }

    private String lookup(String runId) throws Exception {
        Map<String, Object> payload = Map.of(
                "size", 50,
                "_source", java.util.List.of("contents.traceID"),
                "query", Map.of("match", Map.of("contents.attribute", runId)));
        HttpRequest request = HttpRequest.newBuilder(searchEndpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return "";
        }
        JsonNode hits = json.readTree(response.body()).path("hits").path("hits");
        Set<String> traceIds = new LinkedHashSet<>();
        if (hits.isArray()) {
            hits.forEach(hit -> {
                String traceId = hit.path("_source").path("contents").path("traceID").asText();
                if (!traceId.isBlank()) {
                    traceIds.add(traceId);
                }
            });
        }
        return traceIds.size() == 1 ? traceIds.iterator().next() : "";
    }

    private static URI searchEndpoint(URI base) {
        if (base == null) {
            throw new IllegalArgumentException("SAA Elasticsearch URL is required");
        }
        String value = base.toString().replaceAll("/+$", "");
        if (!value.endsWith("/_search")) {
            value += "/loongsuite_traces/_search";
        }
        return URI.create(value);
    }
}
