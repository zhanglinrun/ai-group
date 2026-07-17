package com.linrun.agent.domain.agent.runtime.llm;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * Couples one provider future with the mandatory billing/ledger finalization step.
 *
 * <p>{@link CompletableFuture#whenComplete(BiConsumer)} returns a new stage. Ignoring that
 * stage silently drops an exception raised while settling quota, which can make the model
 * call look successful even though billing failed. This helper makes finalization part of
 * the returned future and keeps cancellation flowing to the provider future.</p>
 */
public final class LlmCallFinalizer {

    private LlmCallFinalizer() {
    }

    public static <T> CompletableFuture<T> finalizeCall(
            CompletableFuture<T> source,
            BiConsumer<? super T, ? super Throwable> finalizer) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(finalizer, "finalizer must not be null");

        CompletableFuture<T> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled && !source.isDone()) {
                    source.cancel(mayInterruptIfRunning);
                }
                return cancelled;
            }
        };

        source.whenComplete((value, throwable) -> {
            Throwable sourceFailure = unwrap(throwable);
            Throwable finalizationFailure = null;
            try {
                finalizer.accept(value, sourceFailure);
            } catch (Throwable failure) {
                finalizationFailure = unwrap(failure);
                if (sourceFailure != null && sourceFailure != finalizationFailure) {
                    finalizationFailure.addSuppressed(sourceFailure);
                }
            }

            if (result.isDone()) {
                return;
            }
            if (finalizationFailure != null) {
                result.completeExceptionally(finalizationFailure);
            } else if (sourceFailure instanceof CancellationException || source.isCancelled()) {
                result.cancel(false);
            } else if (sourceFailure != null) {
                result.completeExceptionally(sourceFailure);
            } else {
                result.complete(value);
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
