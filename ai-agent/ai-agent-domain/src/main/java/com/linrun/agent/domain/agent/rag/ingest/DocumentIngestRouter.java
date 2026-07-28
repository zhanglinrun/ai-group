package com.linrun.agent.domain.agent.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档接入路由器：按 MIME 类型与内容长度分发到对应策略。
 *
 * <p>策略模式入口，借鉴 dodo-agentx 的 DocumentIngestRouter：
 * <ul>
 *   <li>image/* → {@link VlmDescribeStrategy}：VLM 生成描述，存描述不存图</li>
 *   <li>text/* 且 ≤ 阈值 → {@link DirectReadStrategy}：直接进 LLM 上下文，不向量化</li>
 *   <li>text/* 且 > 阈值 → {@link ChunkEmbedStrategy}：切分 + embedding + 入 pgvector</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentIngestRouter {

    private static final int DIRECT_READ_THRESHOLD = 2000;

    private final List<IngestStrategy> strategies;

    public DocumentIngestRouter(List<IngestStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 路由并执行接入。
     */
    public DocumentIngestResult route(DocumentIngestRequest request) {
        if (request == null || StringUtils.isBlank(request.getOwnerId())) {
            return DocumentIngestResult.builder()
                    .success(false)
                    .errorMessage("request or ownerId is blank")
                    .build();
        }
        for (IngestStrategy strategy : strategies) {
            if (strategy.supports(request)) {
                log.info("document ingest routed ownerId={} fileName={} mimeType={} strategy={}",
                        request.getOwnerId(), request.getFileName(), request.getMimeType(),
                        strategy.getClass().getSimpleName());
                try {
                    return strategy.ingest(request);
                } catch (Exception e) {
                    log.warn("document ingest failed ownerId={} strategy={} errorType={}",
                            request.getOwnerId(), strategy.getClass().getSimpleName(),
                            e.getClass().getSimpleName(), e);
                    return DocumentIngestResult.builder()
                            .strategyName(strategy.getClass().getSimpleName())
                            .success(false)
                            .errorMessage(e.getMessage())
                            .build();
                }
            }
        }
        log.warn("no ingest strategy matched ownerId={} mimeType={}",
                request.getOwnerId(), request.getMimeType());
        return DocumentIngestResult.builder()
                .success(false)
                .errorMessage("no strategy matched for mimeType=" + request.getMimeType())
                .build();
    }

    /**
     * 判断是否走直读策略（供外部预判或测试断言）。
     */
    public boolean shouldDirectRead(String mimeType, String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        return isTextMime(mimeType) && content.length() <= DIRECT_READ_THRESHOLD;
    }

    static boolean isImageMime(String mimeType) {
        return StringUtils.isNotBlank(mimeType) && mimeType.toLowerCase().startsWith("image/");
    }

    static boolean isTextMime(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return true;
        }
        String lower = mimeType.toLowerCase();
        return lower.startsWith("text/") || lower.equals("application/pdf")
                || lower.equals("application/json") || lower.equals("application/xml");
    }

    static int directReadThreshold() {
        return DIRECT_READ_THRESHOLD;
    }
}
