package com.aigroup.paymall.domain.benefit.adapter.repository;

import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;

import java.util.Date;
import java.util.List;

public interface IBenefitEventRepository {

    BenefitEventEntity findByOrderIdAndEventType(String orderId, String eventType);

    void insert(BenefitEventEntity entity);

    void markPublished(String eventId);

    List<BenefitEventEntity> queryPendingGrants(String eventType, Date since, Long lastId, int pageSize);

    List<BenefitEventEntity> queryUnpublished(String eventType, int limit);

}
