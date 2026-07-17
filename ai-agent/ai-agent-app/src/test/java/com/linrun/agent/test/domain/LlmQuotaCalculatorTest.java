package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.llm.LlmQuotaCalculator;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import com.linrun.agent.domain.agent.runtime.llm.LlmUsageSettlement;

public class LlmQuotaCalculatorTest {

    @Test
    public void shouldUseMicrocreditsAndShortenOnlyOutputBudget() {
        var amounts = LlmQuotaCalculator.reservation(1_000, 2_000, 5L, 30L);

        Assert.assertEquals(65_000L, amounts.requestedMicrocredits());
        Assert.assertEquals(12_680L, amounts.minimumMicrocredits());
        Assert.assertEquals(500, LlmQuotaCalculator.affordableOutputTokens(
                20_000L, 1_000, 2_000, 5L, 30L));
        Assert.assertTrue(LlmQuotaCalculator.affordableOutputTokens(
                12_650L, 1_000, 2_000, 5L, 30L) < LlmQuotaCalculator.MIN_OUTPUT_TOKENS);
        Assert.assertEquals(8_000L, LlmQuotaCalculator.charge(1_000, 100, 5L, 30L));
    }

    @Test
    public void shouldPreferProviderUsageAndFallbackWithoutSafetyFactor() {
        var provider = LlmUsageSettlement.resolve(1_000, 100, 900, 80, 5L, 30L);
        var estimated = LlmUsageSettlement.resolve(null, null, 900, 80, 5L, 30L);

        Assert.assertEquals("PROVIDER", provider.usageSource());
        Assert.assertEquals(8_000L, provider.chargedMicrocredits());
        Assert.assertEquals("ESTIMATED", estimated.usageSource());
        Assert.assertEquals(6_900L, estimated.chargedMicrocredits());
    }

    @Test
    public void shouldFallbackWhenStreamingProviderReportsZeroUsage() {
        var usage = LlmUsageSettlement.resolve(0, 0, 900, 80, 5L, 30L);

        Assert.assertEquals("ESTIMATED", usage.usageSource());
        Assert.assertEquals(900, usage.inputTokens());
        Assert.assertEquals(80, usage.outputTokens());
        Assert.assertEquals(6_900L, usage.chargedMicrocredits());
    }

    @Test
    public void shouldReleaseFailedCallWithoutTrustworthyProviderUsage() {
        var usage = LlmUsageSettlement.resolveFailure(
                null, null, 3_205, 0, 5L, 30L, false);

        Assert.assertFalse(usage.billable());
        Assert.assertEquals("UNAVAILABLE", usage.usageSource());
        Assert.assertEquals(0, usage.inputTokens());
        Assert.assertEquals(0, usage.outputTokens());
        Assert.assertEquals(0L, usage.chargedMicrocredits());
    }

    @Test
    public void shouldBillFailedStreamAfterRealPartialProviderOutput() {
        var usage = LlmUsageSettlement.resolveFailure(
                null, null, 900, 80, 5L, 30L, true);

        Assert.assertTrue(usage.billable());
        Assert.assertEquals("ESTIMATED", usage.usageSource());
        Assert.assertEquals(6_900L, usage.chargedMicrocredits());
    }

    @Test
    public void shouldBillFailedPostProcessingWhenProviderUsageIsAvailable() {
        var usage = LlmUsageSettlement.resolveFailure(
                1_000, 100, 900, 80, 5L, 30L, false);

        Assert.assertTrue(usage.billable());
        Assert.assertEquals("PROVIDER", usage.usageSource());
        Assert.assertEquals(8_000L, usage.chargedMicrocredits());
    }

    @Test
    public void shouldKeepPartialProviderUsageAsMixedEvidenceOnFailure() {
        var usage = LlmUsageSettlement.resolveFailure(
                1_000, null, 900, 80, 5L, 30L, false);

        Assert.assertTrue(usage.billable());
        Assert.assertEquals("MIXED", usage.usageSource());
        Assert.assertEquals(1_000, usage.inputTokens());
        Assert.assertEquals(80, usage.outputTokens());
        Assert.assertEquals(7_400L, usage.chargedMicrocredits());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroOutputRate() {
        LlmQuotaCalculator.affordableOutputTokens(10_000L, 100, 1_000, 5L, 0L);
    }

    @Test
    public void shouldEstimateChineseTextWithO200kInsteadOfCharacters() {
        String text = "你好，这是一个用于验证本地 Token 估算的复杂问题。";
        int tokens = new TokenCounter().countText(text);

        Assert.assertTrue(tokens > 0);
        Assert.assertTrue(tokens < text.length());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailLoudlyWhenProviderUsageExceedsReservation() {
        LlmQuotaCalculator.requireWithinReservation(65_001L, 65_000L, "LLM call");
    }
}
