package com.linrun.agent.domain.agent.runtime.tool.common;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class AnalyzeFileTool implements BaseTool {

    private static final int DIRECT_TEXT_BYTES = 200 * 1024;
    private static final int MAX_DIRECT_CHARS = 30000;
    private static final Set<String> IMAGE_SUFFIXES =
            Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");

    private AgentContext agentContext;

    @Override
    public String getName() {
        return "analyze_file";
    }

    @Override
    public String getDescription() {
        String names = agentContext.getProductFiles().stream()
                .map(File::getFileName)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining("、"));
        return "分析本轮上传的文档或图片。小文本直接读取，图片和大文件按问题检索。可用文件：" + names;
    }

    @Override
    public Map<String, Object> toParams() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "fileName", Map.of("type", "string", "description", "要分析的已上传文件名"),
                        "question", Map.of("type", "string", "description", "希望从文件中回答的问题")
                ),
                "required", List.of("fileName", "question")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input) {
        Map<String, Object> params = input instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String fileName = String.valueOf(params.getOrDefault("fileName", "")).trim();
        String question = String.valueOf(params.getOrDefault("question", agentContext.getQuery())).trim();
        File file = agentContext.getProductFiles().stream()
                .filter(item -> fileName.equals(item.getFileName()) || fileName.equals(item.getOriginFileName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到已上传文件: " + fileName));

        if (isImage(fileName) || file.getFileSize() == null || file.getFileSize() > DIRECT_TEXT_BYTES) {
            MultiModalAgent delegate = new MultiModalAgent();
            delegate.setAgentContext(agentContext);
            return delegate.execute(Map.of("question", question));
        }

        String url = firstNonBlank(file.getOriginDomainUrl(), file.getDomainUrl(),
                file.getOriginOssUrl(), file.getOssUrl());
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("文件缺少可读取地址: " + fileName);
        }
        try {
            String content = filePort().readText(url, 60L);
            if (content == null) {
                return "文件内容为空: " + fileName;
            }
            return content.length() <= MAX_DIRECT_CHARS
                    ? content
                    : content.substring(0, MAX_DIRECT_CHARS) + "\n\n[内容过长，已截断]";
        } catch (Exception e) {
            throw new IllegalStateException("读取文件失败: " + fileName, e);
        }
    }

    private boolean isImage(String fileName) {
        String normalized = fileName.toLowerCase();
        return IMAGE_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) return value;
        }
        return "";
    }

    private FileArtifactPort filePort() {
        if (agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("analyze_file 缺少运行时依赖");
        }
        return agentContext.getRuntimeDependencies().requireFileArtifactPort();
    }
}
