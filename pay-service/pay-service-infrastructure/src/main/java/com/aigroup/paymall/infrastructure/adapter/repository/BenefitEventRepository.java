package com.aigroup.paymall.infrastructure.adapter.repository;

import com.aigroup.paymall.domain.benefit.adapter.repository.IBenefitEventRepository;
import com.aigroup.paymall.domain.benefit.model.entity.BenefitEventEntity;
import com.aigroup.paymall.infrastructure.dao.IBenefitEventDao;
import com.aigroup.paymall.infrastructure.dao.po.BenefitEvent;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class BenefitEventRepository implements IBenefitEventRepository {

    @Resource
    private IBenefitEventDao benefitEventDao;

    @Override
    public BenefitEventEntity findByOrderIdAndEventType(String orderId, String eventType) {
        BenefitEvent row = benefitEventDao.findByOrderIdAndEventType(orderId, eventType);
        return toEntity(row);
    }

    @Override
    public void insert(BenefitEventEntity entity) {
        benefitEventDao.insert(BenefitEvent.builder()
                .eventId(entity.getEventId())
                .eventType(entity.getEventType())
                .userId(entity.getUserId())
                .orderId(entity.getOrderId())
                .productCode(entity.getProductCode())
                .eventPublished(false)
                .baseQuota(entity.getBaseQuota() == null ? 0L : entity.getBaseQuota())
                .build());
    }

    @Override
    public void markPublished(String eventId) {
        benefitEventDao.markPublished(eventId);
    }

    @Override
    public List<BenefitEventEntity> queryPendingGrants(String eventType, Date since, Long lastId, int pageSize) {
        return benefitEventDao.queryPendingGrants(eventType, since, lastId, pageSize).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<BenefitEventEntity> queryUnpublished(int limit) {
        return benefitEventDao.queryUnpublished(limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private BenefitEventEntity toEntity(BenefitEvent row) {
        if (row == null) {
            return null;
        }
        return BenefitEventEntity.builder()
                .id(row.getId())
                .eventId(row.getEventId())
                .eventType(row.getEventType())
                .userId(row.getUserId())
                .orderId(row.getOrderId())
                .productCode(row.getProductCode())
                .eventPublished(row.getEventPublished())
                .baseQuota(row.getBaseQuota())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime())
                .build();
    }

}
