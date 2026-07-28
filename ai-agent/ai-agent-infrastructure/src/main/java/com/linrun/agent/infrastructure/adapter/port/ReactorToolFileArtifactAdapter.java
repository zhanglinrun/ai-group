package com.linrun.agent.infrastructure.adapter.port;

import com.linrun.agent.types.common.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Resource;

/**
 * 基于 runtime/tools 既有文件接口的文件产物适配器。
 */
@Component
public class ReactorToolFileArtifactAdapter implements FileArtifactPort {

    private final RemoteHttpPort remoteHttpPort;

    @Resource
    private ReactorConfig reactorConfig;

    public ReactorToolFileArtifactAdapter(RemoteHttpPort remoteHttpPort) {
        this.remoteHttpPort = Objects.requireNonNull(remoteHttpPort, "RemoteHttpPort must not be null");
    }

    @Override
    public FileResponse upload(String serviceBaseUrl, FileRequest request) throws IOException {
        FileRequest normalizedRequest = normalizeRequest(request);
        String responseText = remoteHttpPort.execute(RemoteHttpRequest.builder()
                .method("POST")
                .url(normalizeBaseUrl(serviceBaseUrl) + "/v1/file_tool/upload_file")
                .headers(ReactorToolRequestHeaders.json(reactorConfig))
                .body(JsonUtils.toJson(normalizedRequest))
                .connectTimeoutSeconds(60L)
                .readTimeoutSeconds(300L)
                .writeTimeoutSeconds(300L)
                .callTimeoutSeconds(300L)
                .build());
        return JsonUtils.parseObject(responseText, FileResponse.class);
    }

    @Override
    public FileResponse get(String serviceBaseUrl, FileRequest request) throws IOException {
        FileRequest normalizedRequest = normalizeRequest(request);
        String responseText = remoteHttpPort.execute(RemoteHttpRequest.builder()
                .method("POST")
                .url(normalizeBaseUrl(serviceBaseUrl) + "/v1/file_tool/get_file")
                .headers(ReactorToolRequestHeaders.json(reactorConfig))
                .body(JsonUtils.toJson(normalizedRequest))
                .connectTimeoutSeconds(60L)
                .readTimeoutSeconds(300L)
                .writeTimeoutSeconds(300L)
                .callTimeoutSeconds(300L)
                .build());
        return JsonUtils.parseObject(responseText, FileResponse.class);
    }

    @Override
    public String readText(String url, Long timeoutSeconds) throws IOException {
        return remoteHttpPort.execute(RemoteHttpRequest.builder()
                .method("GET")
                .url(url)
                .connectTimeoutSeconds(timeoutSeconds)
                .readTimeoutSeconds(timeoutSeconds)
                .writeTimeoutSeconds(timeoutSeconds)
                .callTimeoutSeconds(timeoutSeconds)
                .build());
    }

    private FileRequest normalizeRequest(FileRequest request) {
        if (request == null) {
            return new FileRequest();
        }
        return FileRequest.builder()
                .requestId(request.getRequestId())
                .fileName(request.getFileName())
                .description(request.getDescription())
                .content(request.getContent())
                .build();
    }

    private String normalizeBaseUrl(String serviceBaseUrl) {
        String normalized = StringUtils.trimToEmpty(serviceBaseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("serviceBaseUrl must not be blank");
        }
        return normalized;
    }
}
