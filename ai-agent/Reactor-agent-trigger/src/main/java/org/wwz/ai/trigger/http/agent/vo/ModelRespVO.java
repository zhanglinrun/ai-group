package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户可选模型响应 VO。
 * 仅暴露展示所需字段，绝不包含 apiKey / baseUrl 等敏感配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRespVO {

    /** 全局唯一模型 ID，随对话请求回传作为选择依据。 */
    private String modelId;

    /** 模型名称（展示用）。 */
    private String modelName;

    /** 模型类型：openai / deepseek / claude 等，前端按此分组当作"厂商"。 */
    private String modelType;
}
