package org.wwz.ai.domain.agent.runtime.context;

/**
 * 即使只保留必要系统指令与当前用户输入，也无法满足模型输入上限。
 */
public class ContextBudgetExceededException extends IllegalStateException {

    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
