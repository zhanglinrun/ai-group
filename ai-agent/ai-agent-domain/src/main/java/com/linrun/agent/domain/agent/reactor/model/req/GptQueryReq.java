package com.linrun.agent.domain.agent.reactor.model.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GptQueryReq {
    private String query;
    private String sessionId;
    private String requestId;
    /** AUTO / STANDARD / DEEP。仅控制统一 AgentLoop 的规划与验证强度。 */
    private String executionMode;
    /** 是否允许本轮注入联网搜索工具。 */
    private Boolean online;
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
    /**
     * 当前轮上传附件元数据，供统一 Agent Loop 使用。
     */
    private List<FileInformation> sessionFiles;
}
