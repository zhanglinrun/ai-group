package com.linrun.agent.domain.agent.rag.ingest;

/**
 * 文档接入策略：多模态路由的策略接口。
 *
 * <p>借鉴 dodo-agentx 的策略模式：图片走 VLM 生成描述、小文本直读、大文本切分向量化。
 * 每个策略只负责自己擅长的场景，{@link DocumentIngestRouter} 负责按 MIME 与长度分发。</p>
 */
public interface IngestStrategy {

    /**
     * 是否支持该请求（由 router 轮询判断）。
     */
    boolean supports(DocumentIngestRequest request);

    /**
     * 执行接入。
     */
    DocumentIngestResult ingest(DocumentIngestRequest request);
}
