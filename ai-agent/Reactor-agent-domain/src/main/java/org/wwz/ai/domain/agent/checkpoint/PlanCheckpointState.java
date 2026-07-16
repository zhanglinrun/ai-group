package org.wwz.ai.domain.agent.checkpoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.dto.Plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-Solve 的最小可重建状态。
 *
 * <p>消息在写入前会被裁剪、脱敏并移除 tool arguments/base64；原始工具事实仍以执行账本和
 * artifact 为准。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanCheckpointState {

    private String originalQuery;

    /** Immutable request-level model selection captured for reproducible resume. */
    private String modelId;

    private String outputStyle;

    private Plan plan;

    private String nextTask;

    private Integer nextStepIndex;

    private Integer targetedReplanRounds;

    private Integer reflectionTokensUsed;

    @Builder.Default
    private List<Message> planningMessages = new ArrayList<>();

    @Builder.Default
    private List<Message> executorMessages = new ArrayList<>();

    @Builder.Default
    private List<File> artifactReferences = new ArrayList<>();
}
