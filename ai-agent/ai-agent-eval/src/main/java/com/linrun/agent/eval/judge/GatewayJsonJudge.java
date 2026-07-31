package com.linrun.agent.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Calls the approved Java Gateway and accepts only a strict, bounded JSON verdict. */
public final class GatewayJsonJudge implements LlmJudge {
    private final HttpClient client;
    private final URI endpoint;
    private final String bearerToken;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public GatewayJsonJudge(URI endpoint, String bearerToken, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), endpoint, bearerToken, timeout);
    }

    GatewayJsonJudge(HttpClient client, URI endpoint, String bearerToken, Duration timeout) {
        if (endpoint == null) {
            throw new IllegalArgumentException("judge endpoint is required");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("Gateway judge requires a bearer token");
        }
        this.client = client;
        this.endpoint = endpoint;
        this.bearerToken = bearerToken.trim();
        this.timeout = timeout == null ? Duration.ofMinutes(1) : timeout;
    }

    @Override
    public JudgeOutcome judge(JudgeRequest request) {
        try {
            Map<String, Object> payload = Map.of(
                    "task", "researchpilot-evaluation-judge",
                    "responseFormat", "strict_json",
                    "requiredFields", java.util.List.of("verdict", "rationale", "model", "version", "promptHash"),
                    "reportSpec", Map.of(
                            "caseId", request.caseId(),
                            "deterministicFailures", request.deterministicFailures(),
                            "answerExcerpt", request.answerExcerpt(),
                            "citations", request.citations(),
                            "successfulTools", request.successfulTools()));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return JudgeOutcome.unavailable(request.caseId(), "Judge HTTP status " + response.statusCode());
            }
            JsonNode result = unwrap(json.readTree(response.body()));
            String verdict = required(result, "verdict").toUpperCase(java.util.Locale.ROOT);
            if (!verdict.equals("PASS") && !verdict.equals("FAIL") && !verdict.equals("NEEDS_HUMAN_REVIEW")) {
                return JudgeOutcome.unavailable(request.caseId(), "Judge returned an invalid verdict");
            }
            return new JudgeOutcome(request.caseId(), JudgeOutcome.Status.AVAILABLE, verdict,
                    required(result, "rationale"), required(result, "model"), required(result, "version"),
                    required(result, "promptHash"));
        } catch (Exception error) {
            return JudgeOutcome.unavailable(request.caseId(), "Judge unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static JsonNode unwrap(JsonNode node) {
        for (String field : java.util.List.of("data", "result", "output")) {
            JsonNode candidate = node.path(field);
            if (candidate.isObject()) {
                return candidate;
            }
            if (candidate.isTextual() && candidate.asText().trim().startsWith("{")) {
                try {
                    return new ObjectMapper().readTree(candidate.asText());
                } catch (Exception ignored) {
                    return node;
                }
            }
        }
        return node;
    }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing strict judge field " + name);
        }
        return value.asText().trim();
    }
}
