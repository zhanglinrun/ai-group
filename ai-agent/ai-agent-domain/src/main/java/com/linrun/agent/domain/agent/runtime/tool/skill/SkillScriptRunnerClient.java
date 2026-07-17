package com.linrun.agent.domain.agent.runtime.tool.skill;

import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest;
import com.linrun.agent.domain.agent.runtime.dto.skill.ScriptRunnerToolRequest;
import com.linrun.agent.domain.agent.runtime.dto.skill.ScriptRunnerToolResponse;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;

/**
 * Skill 脚本执行客户端，负责调用 reactor-tool。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillScriptRunnerClient {

    private final ReactorConfig reactorConfig;
    private final RemoteHttpPort remoteHttpPort;

    public ScriptRunnerToolResponse run(ScriptRunnerToolRequest request) {
        long startedAt = System.nanoTime();
        try {
            String baseUrl = normalizeBaseUrl(reactorConfig.getCodeInterpreterUrl());
            if (baseUrl.isBlank()) {
                throw new SkillLoadException("reactor-tool url is not configured");
            }

            long timeoutSeconds = Math.max(readTimeoutSeconds(request) + 30L, 60L);
            String responseText = remoteHttpPort.execute(RemoteHttpRequest.builder()
                    .method("POST")
                    .url(baseUrl + "/v1/tool/script_runner")
                    .headers(ReactorToolRequestHeaders.json(reactorConfig))
                    .body(JSONObject.toJSONString(request))
                    .connectTimeoutSeconds(timeoutSeconds)
                    .readTimeoutSeconds(timeoutSeconds)
                    .writeTimeoutSeconds(timeoutSeconds)
                    .callTimeoutSeconds(timeoutSeconds)
                    .build());
            if (responseText == null || responseText.isBlank()) {
                throw new SkillLoadException("script runner returned empty response");
            }
            ScriptRunnerToolResponse response = JSONObject.parseObject(responseText, ScriptRunnerToolResponse.class);
            if (response == null) {
                throw new SkillLoadException("script runner returned invalid response");
            }
            return response;
        } catch (SkillLoadException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} script runner call failed runtime={} argumentCount={} argvCount={} timeoutSeconds={} errorType={} durationMs={}",
                    request == null ? null : request.getRequestId(),
                    request == null ? null : request.getRuntime(),
                    request == null || request.getArguments() == null ? 0 : request.getArguments().size(),
                    request == null || request.getArgv() == null ? 0 : request.getArgv().size(),
                    request == null ? null : request.getTimeoutSeconds(),
                    e.getClass().getSimpleName(),
                    (System.nanoTime() - startedAt) / 1_000_000L);
            throw new SkillLoadException("script runner call failed", e);
        }
    }

    private long readTimeoutSeconds(ScriptRunnerToolRequest request) {
        return request == null || request.getTimeoutSeconds() == null
                ? 120L
                : request.getTimeoutSeconds().longValue();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
