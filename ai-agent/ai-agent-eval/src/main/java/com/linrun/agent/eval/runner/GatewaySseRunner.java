package com.linrun.agent.eval.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.eval.dataset.EvalCase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes a real Agent request through Gateway SSE without depending on an in-process Agent. */
public final class GatewaySseRunner implements EvalCaseRunner {
    private static final Pattern URL = Pattern.compile("https?://[^\\s)>]+", Pattern.CASE_INSENSITIVE);
    private final HttpClient client;
    private final URI endpoint;
    private final String bearerToken;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public GatewaySseRunner(URI endpoint, String bearerToken, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), endpoint, bearerToken, timeout);
    }

    GatewaySseRunner(HttpClient client, URI endpoint, String bearerToken, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        if (this.bearerToken.isBlank()) {
            throw new IllegalArgumentException("Gateway SSE runner requires a bearer token");
        }
    }

    @Override
    public EvalRunObservation run(EvalCase evalCase, int trial) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sessionId = "eval-" + evalCase.id() + "-" + suffix;
        String requestId = "eval-" + evalCase.id() + "-" + suffix;
        Map<String, Object> payload = Map.of(
                "sessionId", sessionId,
                "requestId", requestId,
                "query", evalCase.input(),
                "executionMode", evalCase.mode(),
                "outputStyle", "DEEP".equals(evalCase.mode()) ? "docs" : "text",
                "online", evalCase.online());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return failed(requestId, "Gateway SSE HTTP status " + response.statusCode(), elapsed(started));
        }
        String runId = "";
        String traceId = "";
        String failure = "";
        StringBuilder answer = new StringBuilder();
        List<String> successfulTools = new ArrayList<>();
        Map<String, Map<String, String>> toolParameters = new java.util.LinkedHashMap<>();
        Set<String> traceAttributeNames = new LinkedHashSet<>();
        boolean completed = false;
        boolean fresh = false;
        boolean conflict = false;
        boolean recovered = false;
        int quotaSettlements = 0;
        String eventName = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isBlank() || "[DONE]".equals(data) || !data.startsWith("{")) {
                    continue;
                }
                JsonNode event = json.readTree(data);
                String namedType = eventName;
                String payloadType = text(event, "type");
                if (!namedType.isBlank() && !payloadType.isBlank() && !namedType.equals(payloadType)) {
                    return failed(requestId, "SSE event type mismatch", elapsed(started));
                }
                String type = payloadType.isBlank() ? namedType : payloadType;
                eventName = "";
                requestId = choose(text(event, "requestId", "reqId"), requestId);
                runId = choose(text(event, "runId", "run_id"), runId);
                traceId = choose(text(event, "traceId", "trace_id"), traceId);
                collectAttributeNames(event.path("traceAttributes"), traceAttributeNames);
                if ("tool_end".equals(type) && success(event)) {
                    String tool = textDeep(event, "toolName", "tool_name", "tool");
                    if (!tool.isBlank()) {
                        successfulTools.add(tool);
                        Map<String, String> parameters = parameters(event);
                        if (!parameters.isEmpty()) {
                            toolParameters.put(tool, parameters);
                        }
                    }
                }
                if ("text".equals(type) || "stage_output".equals(type) || "complete".equals(type) || "answer".equals(type)) {
                    String output = "text".equals(type)
                            ? textDeep(event, "delta", "content", "text")
                            : textDeep(event, "answer", "output", "content", "message", "summary", "previewMarkdown");
                    if (!output.isBlank()) {
                        answer.append(output).append('\n');
                    }
                    fresh |= event.path("evidenceFresh").asBoolean(false);
                    conflict |= event.path("conflictPreserved").asBoolean(false);
                }
                recovered |= event.path("recoveryResumed").asBoolean(false);
                quotaSettlements += event.path("quotaSettled").asBoolean(false) ? 1 : 0;
                if ("error".equals(type)) {
                    failure = choose(textDeep(event, "message", "error", "code"), "Gateway emitted error");
                    break;
                }
                if ("complete".equals(type)) {
                    completed = true;
                    break;
                }
            }
        }
        String answerText = answer.toString().trim();
        return new EvalRunObservation(requestId, runId, traceId, answerText, successfulTools,
                toolParameters, urls(answerText), traceAttributeNames, completed, fresh, conflict, recovered,
                quotaSettlements, elapsed(started), 0L,
                containsHiddenReasoning(answerText), failure);
    }

    private static EvalRunObservation failed(String requestId, String failure, long latency) {
        return new EvalRunObservation(requestId, "", "", "", List.of(), List.of(), Set.of(),
                false, false, false, false, 0, latency, 0L, failure);
    }

    private static Map<String, String> parameters(JsonNode event) {
        JsonNode source = firstObject(event, "arguments", "params", "input", "toolArguments");
        if (!source.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) {
                result.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return result;
    }

    private static JsonNode firstObject(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isObject()) {
                return value;
            }
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode nested = firstObject(fields.next().getValue(), names);
                if (nested.isObject()) {
                    return nested;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static boolean containsHiddenReasoning(String answer) {
        String normalized = answer.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("<think>") || normalized.contains("</think>")
                || normalized.contains("chain of thought") || normalized.contains("隐藏推理");
    }

    private static boolean success(JsonNode event) {
        String value = textDeep(event, "status", "result", "success").toLowerCase();
        return value.isBlank() || value.equals("success") || value.equals("ok") || value.equals("true");
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static String textDeep(JsonNode node, String... names) {
        String direct = text(node, names);
        if (!direct.isBlank()) {
            return direct;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                String nested = textDeep(fields.next().getValue(), names);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private static void collectAttributeNames(JsonNode node, Set<String> target) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(target::add);
        }
        if (node.isArray()) {
            node.forEach(value -> collectAttributeNames(value, target));
        }
    }

    private static List<String> urls(String text) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return List.copyOf(urls);
    }

    private static String choose(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
