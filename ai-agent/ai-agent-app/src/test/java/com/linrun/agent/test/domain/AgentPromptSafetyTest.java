package com.linrun.agent.test.domain;

import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.prompt.ToolCallPrompt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentPromptSafetyTest {

    @Test
    public void toolPromptShouldRequestPublicProgressInsteadOfChainOfThought() {
        assertTrue(ToolCallPrompt.SYSTEM_PROMPT.contains("不输出隐藏推理"));
        assertTrue(ToolCallPrompt.NEXT_STEP_PROMPT.contains("可公开的行动摘要"));
        assertTrue(ToolCallPrompt.SYSTEM_PROMPT.contains("Function Calling"));
        assertTrue(ToolCallPrompt.SYSTEM_PROMPT.contains("信息足以回答时停止调用工具"));
        assertTrue(ToolCallPrompt.SYSTEM_PROMPT.contains("不得编造未真正调用过的工具输出"));
        assertFalse(ToolCallPrompt.NEXT_STEP_PROMPT.contains("纯文本思考（Reasoning）"));
        assertFalse(ToolCallPrompt.SYSTEM_PROMPT.contains("Let's think step by step"));
    }
}
