package com.linrun.agent.domain.agent.runtime.tool.common;

import com.linrun.agent.types.common.JsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.CodeInterpreterResponse;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.WebFetchRequest;
import com.linrun.agent.domain.agent.runtime.dto.WebFetchResponse;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FetchedPageToolOutput;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactFormatter.toArtifactRefs;

/**
 * 单网页抓取工具，负责调用 runtime/tools 的 web_fetch 端点并登记文件产物。
 */
@Slf4j
@Data
public class WebFetchTool implements BaseTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int SUMMARY_MAX_LENGTH = 500;
    private static final String UNTRUSTED_CONTENT_OPEN = "<<<UNTRUSTED_WEB_CONTENT>>>";
    private static final String UNTRUSTED_CONTENT_CLOSE = "<<<END_UNTRUSTED_WEB_CONTENT>>>";
    private static final int MAX_RISK_SIGNALS = 12;

    private AgentContext agentContext;

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        String defaultDesc = "这是一个单网页抓取工具，用于读取指定 URL 的正文内容，并把完整正文保存为文件产物。";
        ReactorConfig reactorConfig = requireReactorConfig();
        return StringUtils.isNotBlank(reactorConfig.getWebFetchToolDesc())
                ? reactorConfig.getWebFetchToolDesc()
                : defaultDesc;
    }

    @Override
    public Map<String, Object> toParams() {
        ReactorConfig reactorConfig = requireReactorConfig();
        if (!reactorConfig.getWebFetchToolParams().isEmpty()) {
            return reactorConfig.getWebFetchToolParams();
        }

        Map<String, Object> urlParam = new LinkedHashMap<>();
        urlParam.put("type", "string");
        urlParam.put("description", "需要抓取正文的单个网页 URL，必须以 http:// 或 https:// 开头。");

        Map<String, Object> timeoutParam = new LinkedHashMap<>();
        timeoutParam.put("type", "integer");
        timeoutParam.put("description", "可选下载超时时间，单位秒，默认 30 秒。");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlParam);
        properties.put("timeout_seconds", timeoutParam);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", Collections.singletonList("url"));
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input) {
        try {
            Map<String, Object> params = (Map<String, Object>) input;
            String url = StringUtils.trimToEmpty(valueAsString(params.get("url")));
            if (StringUtils.isBlank(url)) {
                return buildFailurePayload("web_fetch 执行失败：url 不能为空。");
            }
            if (!isAbsoluteHttpUrl(url)) {
                return buildFailurePayload("web_fetch 执行失败：仅支持 http/https 的 URL。");
            }

            WebFetchRequest request = WebFetchRequest.builder()
                    .requestId(resolveRequestId())
                    .url(url)
                    .timeoutSeconds(resolveTimeoutSeconds(params))
                    .build();
            ToolArtifactSource artifactSource = agentContext.requireCurrentToolArtifactSource(getName());
            WebFetchResponse response = callWebFetch(request);
            if (response == null) {
                return buildFailurePayload("web_fetch 执行失败：远端服务未返回结果。");
            }
            if (!Integer.valueOf(200).equals(response.getCode())) {
                return buildFailurePayload("web_fetch 执行失败：" + StringUtils.defaultIfBlank(response.getMessage(), "未知错误"));
            }
            if (response.getData() == null) {
                return buildFailurePayload("web_fetch 执行失败：远端服务返回的 data 为空。");
            }
            WebFetchResponse.DataPayload data = response.getData();
            if (!isAbsoluteHttpUrl(data.getFinalUrl()) || StringUtils.isBlank(data.getContent())) {
                return buildFailurePayload("web_fetch 执行失败：抓取结果缺少有效 finalUrl 或正文。");
            }

            appendGeneratedArtifacts(response, artifactSource);
            emitFileMessage(response, artifactSource);
            return buildSuccessPayload(response, artifactSource, url);
        } catch (Exception e) {
            log.error("{} web_fetch execute error inputType={} inputChars={} errorType={}",
                    requestId(),
                    input == null ? "null" : input.getClass().getSimpleName(),
                    input == null ? 0 : String.valueOf(input).length(),
                    e.getClass().getSimpleName(),
                    e);
            return buildFailurePayload("web_fetch 执行失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知异常"));
        }
    }

    private WebFetchResponse callWebFetch(WebFetchRequest request) {
        ReactorConfig reactorConfig = requireReactorConfig();
        String responseText;
        try {
            responseText = requireRemoteHttpPort().execute(RemoteHttpRequest.builder()
                    .method("POST")
                    .url(normalizeBaseUrl(reactorConfig.getWebFetchUrl()) + "/v1/tool/web_fetch")
                    .headers(ReactorToolRequestHeaders.json(reactorConfig))
                    .body(JsonUtils.toJson(request))
                    .connectTimeoutSeconds(30L)
                    .readTimeoutSeconds((long) request.getTimeoutSeconds())
                    .writeTimeoutSeconds((long) request.getTimeoutSeconds())
                    .callTimeoutSeconds((long) request.getTimeoutSeconds())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("调用 web_fetch 远端服务失败: " + e.getMessage(), e);
        }
        return JsonUtils.parseObject(responseText, WebFetchResponse.class);
    }

    private void appendGeneratedArtifacts(WebFetchResponse response, ToolArtifactSource artifactSource) {
        for (CodeInterpreterResponse.FileInfo fileInfo : response.safeFileInfo()) {
            if (fileInfo == null) {
                continue;
            }
            File file = File.builder()
                    .fileName(fileInfo.getFileName())
                    .ossUrl(fileInfo.getOssUrl())
                    .domainUrl(fileInfo.getDomainUrl())
                    .fileSize(fileInfo.getFileSize())
                    .description(buildFileDescription(response))
                    .isInternalFile(false)
                    .build();
            agentContext.registerGeneratedArtifact(artifactSource, file);
        }
    }

    private void emitFileMessage(WebFetchResponse response, ToolArtifactSource artifactSource) {
        if (agentContext == null || agentContext.getPrinter() == null || CollectionUtils.isEmpty(response.safeFileInfo())) {
            return;
        }
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("command", "抓取网页正文");
        resultMap.put("fileInfo", new ArrayList<>(response.safeFileInfo()));
        if (artifactSource != null) {
            resultMap.put("toolCallId", artifactSource.getToolCallId());
            resultMap.put("toolName", artifactSource.getToolName());
        }
        agentContext.getPrinter().send(new AgentStreamEvent.StageOutput(
                agentContext.getRequestId(), artifactSource == null ? null : artifactSource.getToolCallId(),
                "file", resultMap,
                toArtifactRefs(agentContext.getArtifactBindingsByToolCallId(
                        artifactSource == null ? null : artifactSource.getToolCallId())), true));
    }

    private ToolResultPayload buildSuccessPayload(WebFetchResponse response, ToolArtifactSource artifactSource, String requestedUrl) {
        WebFetchResponse.DataPayload data = response.getData();
        String title = StringUtils.defaultIfBlank(data.getTitle(), "未命名网页");
        String finalUrl = data.getFinalUrl();
        String contentTrust = normalizeContentTrust(data);
        String untrustedContent = wrapUntrustedContent(data.getContent());
        List<String> riskSignals = sanitizeRiskSignals(data.getRiskSignals());
        String summary = abbreviateContent(untrustedContent);
        String observation = "网页抓取完成。标题：" + title
                + "；最终地址：" + finalUrl
                + "；内容信任级别：" + contentTrust
                + "；风险信号：" + (riskSignals.isEmpty() ? "无" : String.join(",", riskSignals))
                + "；内容哈希：" + sha256(untrustedContent)
                + "；正文摘要：" + summary
                + "；完整内容已保存为文件产物。";
        String sourceId = artifactSource == null || StringUtils.isBlank(artifactSource.getToolCallId())
                ? "fetch:" + sha256(finalUrl + "\n" + untrustedContent).substring(0, 20)
                : artifactSource.getToolCallId();
        String artifactId = response.safeFileInfo().isEmpty() ? "" : StringUtils.defaultString(response.safeFileInfo().getFirst().getFileName());
        boolean offlineFixture = Boolean.TRUE.equals((data.getMetadata() == null ? Map.<String, Object>of() : data.getMetadata())
                .get("offline_fixture"))
                || "offline_fixture".equalsIgnoreCase(data.getContentSource());
        return ToolResultPayload.structured(observation, observation, new FetchedPageToolOutput(
                sourceId, requestedUrl, finalUrl, title, untrustedContent, sha256(untrustedContent), contentTrust, riskSignals,
                System.currentTimeMillis(), artifactId, offlineFixture));
    }

    private String normalizeContentTrust(WebFetchResponse.DataPayload data) {
        String declared = data == null ? null : data.getContentTrust();
        if (StringUtils.isBlank(declared) && data != null && data.getMetadata() != null) {
            Object value = data.getMetadata().get("contentTrust");
            declared = value == null ? null : String.valueOf(value);
        }
        if (StringUtils.isBlank(declared)) {
            return "UNTRUSTED";
        }
        if (!"UNTRUSTED".equalsIgnoreCase(declared.trim())) {
            throw new IllegalStateException("web_fetch 返回了不受支持的内容信任级别");
        }
        return "UNTRUSTED";
    }

    private List<String> sanitizeRiskSignals(List<String> rawSignals) {
        if (rawSignals == null || rawSignals.isEmpty()) {
            return List.of();
        }
        return rawSignals.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(signal -> signal.matches("[a-z0-9_]{1,64}"))
                .distinct()
                .limit(MAX_RISK_SIGNALS)
                .toList();
    }

    private String wrapUntrustedContent(String content) {
        String value = StringUtils.defaultString(content);
        if (value.startsWith(UNTRUSTED_CONTENT_OPEN) && value.endsWith(UNTRUSTED_CONTENT_CLOSE)) {
            return value;
        }
        return UNTRUSTED_CONTENT_OPEN + "\n"
                + value.replace("<<<", "‹‹‹")
                + "\n" + UNTRUSTED_CONTENT_CLOSE;
    }

    private ToolResultPayload buildFailurePayload(String message) {
        return ToolResultPayload.failure(message, message, null, message);
    }

    private String buildFileDescription(WebFetchResponse response) {
        WebFetchResponse.DataPayload data = response == null ? null : response.getData();
        if (data == null) {
            return "网页抓取正文";
        }
        return StringUtils.defaultIfBlank(data.getTitle(), data.getFinalUrl());
    }

    private Integer resolveTimeoutSeconds(Map<String, Object> params) {
        Integer timeoutSeconds = valueAsInteger(params.get("timeout_seconds"));
        if (timeoutSeconds == null) {
            timeoutSeconds = valueAsInteger(params.get("timeoutSeconds"));
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
    }

    private String resolveRequestId() {
        if (agentContext == null) {
            return "unknown";
        }
        if (StringUtils.isNotBlank(agentContext.getSessionId())) {
            return agentContext.getSessionId();
        }
        return agentContext.getRequestId();
    }

    private String abbreviateContent(String content) {
        String normalized = StringUtils.normalizeSpace(StringUtils.defaultString(content));
        if (normalized.length() <= SUMMARY_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = StringUtils.trimToEmpty(baseUrl);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalStateException("web_fetch_url 未配置");
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isAbsoluteHttpUrl(String value) {
        try {
            URI uri = new URI(StringUtils.trimToEmpty(value));
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private String sha256(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hash.append(String.format("%02x", value));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required for fetched page provenance", error);
        }
    }

    private String requestId() {
        return agentContext == null ? "unknown" : StringUtils.defaultString(agentContext.getRequestId(), "unknown");
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer valueAsInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ReactorConfig requireReactorConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("WebFetchTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }

    private RemoteHttpPort requireRemoteHttpPort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("WebFetchTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireRemoteHttpPort();
    }
}
