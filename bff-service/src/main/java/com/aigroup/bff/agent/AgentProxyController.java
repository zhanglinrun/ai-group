package com.aigroup.bff.agent;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.context.RequestUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

/** Thin page-level proxy: the browser never receives the Agent service address. */
@RestController
@RequestMapping("/api/bff/agent")
public class AgentProxyController {

    private static final String DISCOVERED_AGENT_URL = "http://agent-service";

    private final WebClient.Builder webClientBuilder;
    private final WebClient.Builder sseWebClientBuilder;
    private final String agentUrl;

    public AgentProxyController(
            WebClient.Builder agentWebClientBuilder,
            @Qualifier("loadBalancedAgentWebClientBuilder") ObjectProvider<WebClient.Builder> loadBalancedBuilder,
            @Qualifier("sseAgentWebClientBuilder") ObjectProvider<WebClient.Builder> sseBuilder,
            @Qualifier("loadBalancedSseAgentWebClientBuilder") ObjectProvider<WebClient.Builder> loadBalancedSseBuilder,
            @Value("${ai-group.agent.url:}") String agentUrl) {
        if (agentUrl == null || agentUrl.isBlank()) {
            this.webClientBuilder = loadBalancedBuilder.getObject();
            this.sseWebClientBuilder = loadBalancedSseBuilder.getObject();
            this.agentUrl = DISCOVERED_AGENT_URL;
        } else {
            this.webClientBuilder = agentWebClientBuilder;
            this.sseWebClientBuilder = sseBuilder.getObject();
            this.agentUrl = trimTrailingSlash(agentUrl);
        }
    }

    @PostMapping("/runs")
    public ResponseEntity<String> createRun(@RequestBody String body, HttpServletRequest request) {
        return json(request, "POST", "/api/runs", body);
    }

    @GetMapping("/runs")
    public ResponseEntity<String> listRuns(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        String path = "/api/runs?limit=" + limit + "&offset=" + offset
                + (status == null ? "" : "&status=" + status);
        return json(request, "GET", path, null);
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<String> getRun(@PathVariable String runId, HttpServletRequest request) {
        return json(request, "GET", "/api/runs/" + runId, null);
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String runId, HttpServletRequest request) {
        RequestUserContext.requireUserId();
        return sseWebClientBuilder.build()
                .get()
                .uri(agentUrl + "/api/runs/" + runId + "/events")
                .headers(headers -> copyIdentity(request, headers))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(event -> {
                    ServerSentEvent.Builder<String> builder = ServerSentEvent.builder();
                    if (event.id() != null) {
                        builder.id(event.id());
                    }
                    if (event.event() != null) {
                        builder.event(event.event());
                    }
                    if (event.retry() != null) {
                        builder.retry(event.retry());
                    }
                    return builder.data(event.data()).build();
                })
                .timeout(Duration.ofMinutes(30))
                // 4xx before the stream starts must surface to the browser.
                // Disconnects after the stream has started stay empty so a
                // navigation away is not logged as an Agent 500.
                .onErrorResume(WebClientResponseException.class, ex -> {
                    if (isClientIdentityOrMissing(ex)) {
                        return Flux.error(ex);
                    }
                    return Flux.empty();
                })
                .onErrorResume(ex -> ex instanceof WebClientResponseException wce && isClientIdentityOrMissing(wce)
                        ? Flux.error(ex)
                        : Flux.empty());
    }

    @PostMapping("/runs/{runId}/plan/confirm")
    public ResponseEntity<String> confirmPlan(@PathVariable String runId, @RequestBody String body, HttpServletRequest request) {
        return json(request, "POST", "/api/runs/" + runId + "/plan/confirm", body);
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<String> resume(@PathVariable String runId, HttpServletRequest request) {
        return json(request, "POST", "/api/runs/" + runId + "/resume", null);
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<String> stop(@PathVariable String runId, HttpServletRequest request) {
        return json(request, "PATCH", "/api/runs/" + runId, "{\"status\":\"cancelled\"}");
    }

    @GetMapping("/runs/{runId}/report")
    public ResponseEntity<String> report(@PathVariable String runId, HttpServletRequest request) {
        return json(request, "GET", "/api/runs/" + runId + "/report", null);
    }

    @GetMapping("/runs/{runId}/evidence")
    public ResponseEntity<String> evidence(@PathVariable String runId, HttpServletRequest request) {
        String query = request.getQueryString();
        String path = "/api/runs/" + runId + "/evidence"
                + (query == null || query.isBlank() ? "" : "?" + query);
        return json(request, "GET", path, null);
    }

    @GetMapping("/runs/{runId}/trace")
    public ResponseEntity<String> trace(@PathVariable String runId, HttpServletRequest request) {
        return json(request, "GET", "/api/runs/" + runId + "/trace", null);
    }

    /** Keep the BFF surface complete while new Agent resources are added. */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<String> passthrough(HttpServletRequest request, @RequestBody(required = false) String body) {
        String prefix = "/api/bff/agent";
        String uri = request.getRequestURI();
        String suffix = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
        String query = request.getQueryString();
        String path = "/api" + (suffix.isBlank() ? "/" : suffix) + (query == null ? "" : "?" + query);
        return json(request, request.getMethod(), path, body);
    }

    private ResponseEntity<String> json(HttpServletRequest request, String method, String path, String body) {
        RequestUserContext.requireUserId();
        WebClient.RequestBodySpec spec = webClientBuilder.build()
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(agentUrl + path)
                .headers(headers -> copyIdentity(request, headers));
        WebClient.RequestHeadersSpec<?> requestSpec = body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        try {
            String result = requestSpec.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(45));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result == null ? "{}" : result);
        } catch (WebClientResponseException ex) {
            // Preserve Agent's status and structured error body.  Returning a
            // generic HTTP 200/500 envelope makes the frontend treat a
            // forbidden or missing run as a successful DTO and can crash
            // while rendering it (for example, `user_query.split(...)`).
            String result = ex.getResponseBodyAsString();
            return ResponseEntity.status(ex.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result == null || result.isBlank() ? "{}" : result);
        }
    }

    private void copyIdentity(HttpServletRequest request, org.springframework.http.HttpHeaders headers) {
        for (String name : new String[]{
                CommonConstant.HEADER_USER_ID,
                CommonConstant.HEADER_USERNAME,
                CommonConstant.HEADER_ROLE,
                CommonConstant.HEADER_GATEWAY_REQUEST,
                CommonConstant.HEADER_INTERNAL_TOKEN,
                CommonConstant.HEADER_INTERNAL_JWT,
        }) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                headers.set(name, value);
            }
        }
    }

    private static boolean isClientIdentityOrMissing(WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        return status == 401 || status == 403 || status == 404;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
