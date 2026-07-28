package com.linrun.agent.domain.agent.rag.ingest;

import lombok.Builder;
import lombok.Data;

/**
 * 文档接入请求：多模态路由的统一输入。
 *
 * <p>由 {@link DocumentIngestRouter} 根据 mimeType 与 content 长度分发到对应策略。</p>
 */
@Data
@Builder
public class DocumentIngestRequest {

    /** 租户隔离 ID */
    private String ownerId;

    /** 会话 ID（可空，用于把记忆关联到具体会话） */
    private String conversationId;

    /** 文件名或来源标识 */
    private String fileName;

    /** MIME 类型：image/* 走 VLM，text/* 与 application/pdf 走文本策略 */
    private String mimeType;

    /** 原始内容：文本类为 UTF-8 字符串，图片类为 base64 或 URL */
    private String content;

    /** base64 二进制（图片场景），与 content 二选一 */
    private String base64Data;
}
