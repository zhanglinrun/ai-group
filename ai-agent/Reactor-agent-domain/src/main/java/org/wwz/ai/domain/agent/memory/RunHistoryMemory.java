package org.wwz.ai.domain.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 run 的历史记忆。
 * 负责承接 run 级输入文件和该 run 下的 ReAct 循环列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunHistoryMemory {

    private Long runId;

    private String requestId;

    private String sessionId;

    private String entryAgent;

    /** 用户原始诉求；作为历史数据而非指令注入。 */
    private String queryText;

    /** run 结束时生成的结论摘要，替代原始 chain-of-thought 回灌。 */
    private String finalSummaryText;

    @Builder.Default
    private List<FileArtifactMemory> sessionInputFiles = new ArrayList<>();

    @Builder.Default
    private List<ReactCycleMemory> reactCycles = new ArrayList<>();
}
