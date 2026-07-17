package com.linrun.agent.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 结束 LLM 调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInvocationFinishRecord {

    private Long llmInvocationId;

    private String requestId;

    private Integer status;

    private String responseText;

    private Integer toolCallCount;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String usageSource;

    private Long chargedMicrocredits;

    private String finishReason;

    private String errorMsg;

    private LocalDateTime finishedAt;
}
