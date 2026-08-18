package com.aigroup.paymall.test.infrastructure;

import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.infrastructure.adapter.repository.BenefitEventRepository;
import com.aigroup.paymall.infrastructure.dao.IBenefitEventDao;
import com.aigroup.paymall.infrastructure.dao.po.BenefitEvent;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class BenefitEventRepositoryTest {

    @Test
    public void insertNormalizesNullableQuotaFieldsForNotNullSchemaColumns() {
        IBenefitEventDao dao = mock(IBenefitEventDao.class);
        BenefitEventRepository repository = new BenefitEventRepository();
        ReflectionTestUtils.setField(repository, "benefitEventDao", dao);

        repository.insert(BenefitEventEntity.builder()
                .eventId("evt-1")
                .eventType("ORDER_PAY_SUCCESS")
                .userId(10001L)
                .orderId("order-1")
                .productCode("QUOTA_LIGHT")
                .baseQuota(null)
                .build());

        ArgumentCaptor<BenefitEvent> rowCaptor = ArgumentCaptor.forClass(BenefitEvent.class);
        verify(dao).insert(rowCaptor.capture());
        assertEquals(Long.valueOf(0L), rowCaptor.getValue().getBaseQuota());
    }
}
