package com.linrun.agent.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史详情返回对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryDetailRespVO {

    private String sessionId;

    private String title;

    private String status;

    private String outputStyle;

    private String executionMode;

    private ConversationRoleRespVO role;

    private Integer runCount;

    private Integer finishedRunCount;

    private Integer failedRunCount;

    private LocalDateTime startedAt;

    private LocalDateTime lastActiveAt;

    @Builder.Default
    private List<RunDetailRespVO> runs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunDetailRespVO {

        private String requestId;

        private String status;

        private String queryText;

        private String finalSummaryText;

        private LocalDateTime startedAt;

        private LocalDateTime finishedAt;

        /** 本轮实际使用的模型名。 */
        private String modelName;

        /** 本轮总 token 用量。 */
        private Integer totalTokens;

        /** 本轮耗时（毫秒）。 */
        private Long durationMs;

        @Builder.Default
        private List<GptProcessResult> replayFrames = new ArrayList<>();
    }
}
