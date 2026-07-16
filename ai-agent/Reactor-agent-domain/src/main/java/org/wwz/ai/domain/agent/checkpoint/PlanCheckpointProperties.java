package org.wwz.ai.domain.agent.checkpoint;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 durable Plan-Solve 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-agent.checkpoint")
public class PlanCheckpointProperties {

    /** checkpoint 写入失败时主任务继续运行；显式 resume 则 fail-closed。 */
    private boolean enabled = true;

    /** 每类 Agent 最多保存的受限消息条数。 */
    private int maxMessagesPerAgent = 40;

    /** 单条消息写入 checkpoint 前的字符上限。 */
    private int maxMessageChars = 4000;

    /** 这些工具可在 checkpoint 后安全重放；其余工具需要用户明确确认。 */
    private List<String> replaySafeTools = new ArrayList<>(List.of(
            "deep_search",
            "web_fetch",
            "read_tool",
            "list_directory_tool",
            "grep_tool",
            "glob_tool"
    ));
}
