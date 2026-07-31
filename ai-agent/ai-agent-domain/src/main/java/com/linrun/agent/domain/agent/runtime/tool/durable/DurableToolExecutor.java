package com.linrun.agent.domain.agent.runtime.tool.durable;

import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;

/** Runtime bridge used by ToolDispatcher for the two P50 remote durable tools. */
public interface DurableToolExecutor {

    ToolResultPayload execute(DurableToolExecutionRequest request);

    default boolean supports(String toolName) {
        return "deep_search".equals(toolName) || "code_interpreter".equals(toolName);
    }
}
