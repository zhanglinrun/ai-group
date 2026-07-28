package com.aigroup.paymall.test.trigger;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.trigger.job.OutboxEventPublishJob;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OutboxEventPublishJobTest {

    @Test
    public void jobDelegatesToIndependentPendingEventPublisher() {
        IBenefitEventService service = mock(IBenefitEventService.class);
        when(service.publishPendingEvents()).thenReturn(2);
        OutboxEventPublishJob job = new OutboxEventPublishJob();
        ReflectionTestUtils.setField(job, "benefitEventService", service);

        job.exec();

        verify(service).publishPendingEvents();
    }

    @Test(expected = IllegalStateException.class)
    public void jobPropagatesPublisherFailureToXxlJob() {
        IBenefitEventService service = mock(IBenefitEventService.class);
        doThrow(new IllegalStateException("broker down")).when(service).publishPendingEvents();
        OutboxEventPublishJob job = new OutboxEventPublishJob();
        ReflectionTestUtils.setField(job, "benefitEventService", service);

        job.exec();
    }
}
