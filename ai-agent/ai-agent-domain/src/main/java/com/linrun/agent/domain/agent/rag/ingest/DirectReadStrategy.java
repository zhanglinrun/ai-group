package com.linrun.agent.domain.agent.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直读策略：小文本（≤ 阈值）直接进 LLM 上下文，不向量化、不落库。
 *
 * <p>设计权衡：短文本（如一段说明、一条 FAQ）向量化反而引入噪声与检索延迟，
 * 直接拼进上下文既快又准。借鉴 dodo-agentx 的 DirectReadStrategy。</p>
 */
@Slf4j
@Component
public class DirectReadStrategy implements IngestStrategy {

    @Override
    public boolean supports(DocumentIngestRequest request) {
        if (request == null || StringUtils.isBlank(request.getContent())) {
            return false;
        }
        return DocumentIngestRouter.isTextMime(request.getMimeType())
                && request.getContent().length() <= DocumentIngestRouter.directReadThreshold();
    }

    @Override
    public DocumentIngestResult ingest(DocumentIngestRequest request) {
        String text = StringUtils.defaultString(request.getContent()).trim();
        log.info("direct read ingest ownerId={} fileName={} chars={}",
                request.getOwnerId(), request.getFileName(), text.length());
        return DocumentIngestResult.builder()
                .strategyName("DIRECT_READ")
                .success(true)
                .memoryIds(List.of())
                .readableText(text)
                .chunkCount(0)
                .build();
    }
}
