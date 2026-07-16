package org.wwz.ai.test.domain;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.prompt.PlanningPrompt;
import org.wwz.ai.domain.agent.runtime.prompt.ToolCallPrompt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentPromptSafetyTest {

    @Test
    public void toolPromptShouldRequestPublicProgressInsteadOfChainOfThought() {
        assertTrue(ToolCallPrompt.SYSTEM_PROMPT.contains("不输出隐藏推理"));
        assertTrue(ToolCallPrompt.NEXT_STEP_PROMPT.contains("可公开的行动摘要"));
        assertFalse(ToolCallPrompt.NEXT_STEP_PROMPT.contains("纯文本思考（Reasoning）"));
        assertFalse(ToolCallPrompt.SYSTEM_PROMPT.contains("Let's think step by step"));
    }

    @Test
    public void planningPromptShouldKeepReasoningPrivate() {
        assertTrue(PlanningPrompt.SYSTEM_PROMPT.contains("不输出隐藏推理"));
        assertTrue(PlanningPrompt.NEXT_STEP_PROMPT.contains("可公开的简短计划进度摘要"));
        assertFalse(PlanningPrompt.SYSTEM_PROMPT.contains("Let's think step by step"));
        assertFalse(PlanningPrompt.NEXT_STEP_PROMPT.contains("先输出简短思考"));
    }
}
