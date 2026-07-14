package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.evaluation.PlanReflectionBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlanReflectionBudgetTest {

    @Test
    public void shouldNeverExceedBudgetUnderConcurrentReservations() throws Exception {
        PlanReflectionBudget budget = new PlanReflectionBudget(1000);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> reservations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            reservations.add(executor.submit(() -> {
                start.await();
                return budget.tryConsume(25);
            }));
        }
        start.countDown();
        int accepted = 0;
        for (java.util.concurrent.Future<Boolean> reservation : reservations) {
            if (reservation.get()) {
                accepted++;
            }
        }
        executor.shutdownNow();

        Assert.assertEquals(40, accepted);
        Assert.assertEquals(1000, budget.used());
        Assert.assertEquals(0, budget.remaining());
    }
}
