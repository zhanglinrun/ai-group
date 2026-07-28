package com.linrun.agent.domain.agent.runtime.tool.common;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestRequest;
import com.linrun.agent.domain.agent.rag.ingest.DocumentIngestResult;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalHit;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class AnalyzeFileTool implements BaseTool {

    private static final int DIRECT_TEXT_BYTES = 200 * 1024;
    static final int MAX_DIRECT_CHARS = 30000;
    private static final Set<String> IMAGE_SUFFIXES =
            Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");
    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".md", ".txt", ".json", ".yaml", ".yml", ".csv");

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
                .orElseThrow(() -> new IllegalArgumentException("未找到已上传文件: " + fileName
                        + "；当前可用文件: " + agentContext.getProductFiles().stream()
                        .map(File::getFileName)
                        .filter(StringUtils::isNotBlank)
                        .collect(java.util.stream.Collectors.joining("、"))));

        String url = firstNonBlank(file.getOriginDomainUrl(), file.getDomainUrl(),
                file.getOriginOssUrl(), file.getOssUrl());
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("文件缺少可读取地址: " + fileName);
        }
        try {
            boolean image = isImage(fileName);
            String content = image ? url : filePort().readText(url, 60L);
            if (content == null) {
                return "文件内容为空: " + fileName;
            }
            var dependencies = agentContext.getRuntimeDependencies();
            if (dependencies == null || dependencies.getDocumentIngestRouter() == null) {
                if (!image && content.length() <= MAX_DIRECT_CHARS) return content;
                throw new IllegalStateException("analyze_file 缺少 PostgreSQL 文档摄取能力");
            }
            String ownerId = agentContext.getOwnerId() == null
                    ? agentContext.getSessionId() : String.valueOf(agentContext.getOwnerId());
            DocumentIngestResult result = dependencies.getDocumentIngestRouter().route(
                    DocumentIngestRequest.builder()
                            .ownerId(ownerId)
                            .conversationId(agentContext.getSessionId())
                            .fileName(fileName)
                            .mimeType(mimeType(fileName))
                            .content(content)
                            .build());
            if (!result.isSuccess()) {
                throw new IllegalStateException("文件摄取失败: " + result.getErrorMessage());
            }
            if ("DIRECT_READ".equals(result.getStrategyName()) || dependencies.getHybridRetriever() == null) {
                return result.getReadableText();
            }
            List<HybridRetrievalHit> hits = dependencies.getHybridRetriever().retrieve(
                    HybridRetrievalRequest.builder()
                            .ownerId(ownerId)
                            .query(question)
                            .docTypes(image ? List.of("image_description") : List.of("file_chunk"))
                            .metadataFilters(Map.of("fileName", fileName))
                            .topK(6)
                            .scoreThreshold(0.2d)
                            .keywordEnabled(true)
                            .build());
            return hits.isEmpty()
                    ? result.getReadableText()
                    : hits.stream().map(HybridRetrievalHit::getContent)
                            .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            throw new IllegalStateException("读取文件失败: " + fileName, e);
        }
    }

    private boolean isImage(String fileName) {
        String normalized = fileName.toLowerCase();
        return IMAGE_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private boolean isText(String fileName) {
        String normalized = fileName.toLowerCase();
        return TEXT_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private String mimeType(String fileName) {
        String normalized = fileName.toLowerCase();
        if (IMAGE_SUFFIXES.stream().anyMatch(normalized::endsWith)) {
            if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) return "image/jpeg";
            if (normalized.endsWith(".webp")) return "image/webp";
            if (normalized.endsWith(".gif")) return "image/gif";
            return "image/png";
        }
        if (normalized.endsWith(".json")) return "application/json";
        if (normalized.endsWith(".csv")) return "text/csv";
        if (normalized.endsWith(".md")) return "text/markdown";
        return "text/plain";
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
