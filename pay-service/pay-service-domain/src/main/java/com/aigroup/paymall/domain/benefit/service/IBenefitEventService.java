package com.aigroup.paymall.domain.benefit.service;

import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;

import java.util.Date;
import java.util.List;

public interface IBenefitEventService {

    void enqueueCompletedOrderEvents(List<String> orderIds);

    void enqueueRevokedBenefitEvents(List<String> orderIds);

    int publishPendingEvents();

    List<BenefitEventEntity> queryPendingGrants(Date since, Long lastId, int pageSize);

}
