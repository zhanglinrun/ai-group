package org.wwz.ai.domain.agent.reactor.model.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.reactor.model.dto.FileInformation;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GptQueryReq {
    private String query;
    private String sessionId;
    private String requestId;
    private Integer deepThink;
    /**
     * 前端传入交付物格式：html(网页模式）,docs(文档模式）， table(表格模式）
     */
    private String outputStyle;
    private String traceId;
    private String user;
    private String aiAgentId;
    /**
     * 用户在本轮对话选择的模型 ID（可空）。命中启用模型目录时覆盖默认模型，否则忽略并走默认逻辑。
     */
    private String modelId;
    /** Plan-Solve 显式恢复点；为空表示创建普通新运行。 */
    private String resumeCheckpointId;
    /** SAFE_ONLY（默认）或经用户确认后的 RESTART_FROM_CHECKPOINT。 */
    private String resumeDecision;
    /**
     * 当前轮上传附件元数据，供 ReAct / PlanSolve 链路桥接到会话上下文。
     */
    private List<FileInformation> sessionFiles;
}
