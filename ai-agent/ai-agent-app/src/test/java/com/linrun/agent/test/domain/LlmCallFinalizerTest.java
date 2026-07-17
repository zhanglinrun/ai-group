package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.llm.LlmCallFinalizer;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmCallFinalizerTest {

    @Test
    public void shouldPropagateBillingFinalizationFailure() throws Exception {
        CompletableFuture<String> source = CompletableFuture.completedFuture("provider-response");

        CompletableFuture<String> result = LlmCallFinalizer.finalizeCall(source, (value, failure) -> {
            throw new IllegalStateException("quota settlement failed");
        });

        try {
            result.get();
            Assert.fail("billing failure must fail the returned model future");
        } catch (ExecutionException expected) {
            Assert.assertEquals("quota settlement failed", expected.getCause().getMessage());
        }
    }

    @Test
    public void shouldFinalizeExactlyOnceAndKeepSuccessfulValue() throws Exception {
        AtomicInteger finalized = new AtomicInteger();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> result = LlmCallFinalizer.finalizeCall(
                source, (value, failure) -> finalized.incrementAndGet());

        source.complete("ok");

        Assert.assertEquals("ok", result.get());
        Assert.assertEquals(1, finalized.get());
    }

    @Test
    public void shouldPropagateCancellationToProviderAndStillFinalize() {
        AtomicInteger finalized = new AtomicInteger();
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> result = LlmCallFinalizer.finalizeCall(
                source, (value, failure) -> finalized.incrementAndGet());

        Assert.assertTrue(result.cancel(true));

        Assert.assertTrue(source.isCancelled());
        Assert.assertEquals(1, finalized.get());
    }
}
