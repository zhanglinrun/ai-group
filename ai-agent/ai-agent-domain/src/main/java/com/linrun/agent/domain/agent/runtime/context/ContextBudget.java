package com.linrun.agent.domain.agent.runtime.context;

/**
 * 单次模型调用的全局输入预算。
 *
 * <p>预算同时覆盖消息、系统提示、记忆和工具 schema。工具 schema 无法通过消息裁剪缩小，
 * 因此作为 fixedTokens 预先扣除；safetyMarginTokens 用于协议格式等小量不可见开销。</p>
 */
public record ContextBudget(int maxInputTokens,
                            int fixedTokens,
                            int safetyMarginTokens,
                            int maxUntrustedContentTokens) {

    private static final int DEFAULT_MAX_UNTRUSTED_TOKENS = 2048;

    public ContextBudget {
        fixedTokens = Math.max(0, fixedTokens);
        safetyMarginTokens = Math.max(0, safetyMarginTokens);
        maxUntrustedContentTokens = Math.max(64, maxUntrustedContentTokens);
    }

    public static ContextBudget forModel(int maxInputTokens, int fixedTokens) {
        if (maxInputTokens <= 0) {
            return new ContextBudget(Integer.MAX_VALUE, fixedTokens, 0, DEFAULT_MAX_UNTRUSTED_TOKENS);
        }
        int safetyMargin = maxInputTokens >= 1024
                ? Math.min(256, Math.max(64, maxInputTokens / 100))
                : 16;
        int messageBudget = Math.max(0, maxInputTokens - Math.max(0, fixedTokens) - safetyMargin);
        int untrustedBudget = Math.min(
                DEFAULT_MAX_UNTRUSTED_TOKENS,
                Math.max(64, messageBudget / 3)
        );
        return new ContextBudget(maxInputTokens, fixedTokens, safetyMargin, untrustedBudget);
    }

    public boolean isBounded() {
        return maxInputTokens != Integer.MAX_VALUE;
    }

    public int messageTokenBudget() {
        if (!isBounded()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxInputTokens - fixedTokens - safetyMarginTokens);
    }
}
