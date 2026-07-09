package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.printer.Printer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 主循环死循环兜底与断开中止测试（无需 LLM/中间件，离线可跑）。
 */
public class AgentLoopGuardTest {

    /**
     * 每步返回固定结果的最小 Agent，用于验证 run() 的通用终止逻辑。
     */
    static class ConstantStepAgent extends BaseAgent {
        private final String stepResult;
        private final AtomicInteger stepCalls = new AtomicInteger();

        ConstantStepAgent(String stepResult, int maxSteps, int duplicateThreshold) {
            this.stepResult = stepResult;
            setName("test-agent");
            setMaxSteps(maxSteps);
            setDuplicateThreshold(duplicateThreshold);
        }

        @Override
        public String step() {
            stepCalls.incrementAndGet();
            return stepResult;
        }

        int stepCalls() {
            return stepCalls.get();
        }
    }

    @Test
    public void shouldBreakOnRepeatedStepsBeforeMaxSteps() {
        ConstantStepAgent agent = new ConstantStepAgent("same-observation", 40, 2);

        String last = agent.run("请开始");

        // 连续重复达到阈值即以可识别的死循环终止标识收尾，而不是空转到 40 步
        Assert.assertEquals(BaseAgent.TERMINATION_STUCK, last);
        Assert.assertTrue("stuck should stop well before maxSteps", agent.stepCalls() <= 4);
    }

    @Test
    public void shouldRunToMaxStepsWhenDuplicateDetectionDisabled() {
        ConstantStepAgent agent = new ConstantStepAgent("obs", 5, 0);

        String last = agent.run("请开始");

        Assert.assertTrue(last.startsWith(BaseAgent.TERMINATION_MAX_STEPS));
        Assert.assertEquals(5, agent.stepCalls());
    }

    @Test
    public void shouldStopImmediatelyWhenDownstreamAborted() {
        ConstantStepAgent agent = new ConstantStepAgent("obs", 40, 0);
        Printer abortedPrinter = Mockito.mock(Printer.class);
        Mockito.when(abortedPrinter.isAborted()).thenReturn(true);
        agent.setPrinter(abortedPrinter);

        String last = agent.run("请开始");

        // 断开后不应执行任何步骤
        Assert.assertEquals(0, agent.stepCalls());
        Assert.assertEquals("No steps executed", last);
    }
}
