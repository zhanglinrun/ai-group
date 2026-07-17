package com.linrun.agent.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.time.LocalDateTime;

/**
 * 完成工具调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationFinishRecord {

    private Long toolInvocationId;

    private Long runId;

    private String requestId;

    private String sessionId;

    private String toolCallId;

    private String toolName;

    private Integer status;

    private String toolResult;

    private String llmObservation;

    private ToolStructuredOutput structuredOutput;

    private String errorMsg;

    private LocalDateTime finishedAt;
}
