package com.linrun.agent.domain.agent.rag.ingest;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档接入结果：策略执行后的统一输出。
 *
 * <p>无论走哪条策略，都产出可被后续检索/记忆链路消费的结构化结果。</p>
 */
@Data
@Builder
public class DocumentIngestResult {

    /** 使用的策略名：VLM_DESCRIBE | DIRECT_READ | CHUNK_EMBED */
    private String strategyName;

    /** 是否成功 */
    private boolean success;

    /** 写入 agent_semantic_memory 的主键列表（DIRECT_READ 不写库时为空） */
    private List<String> memoryIds;

    /** 策略产出的可读文本（VLM 描述 / 原文 / 切分摘要），供 LLM 上下文直接引用 */
    private String readableText;

    /** 切分块数（CHUNK_EMBED 专有） */
    private int chunkCount;

    /** 失败原因 */
    private String errorMessage;
}
