package com.aigroup.paymall.domain.benefit.service;

import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;

import java.util.Date;
import java.util.List;

public interface IBenefitEventService {

    void publishGroupBuyCompletedEvents(List<String> orderIds, Long bonusQuota);

    void publishGroupBuyRevokedEvents(List<String> orderIds);

    int republishPendingEvents();

    List<BenefitEventEntity> queryPendingGrants(Date since, Long lastId, int pageSize);

}
