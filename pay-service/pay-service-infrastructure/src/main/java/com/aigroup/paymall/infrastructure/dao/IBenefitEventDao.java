package com.aigroup.paymall.infrastructure.dao;

import com.aigroup.paymall.infrastructure.dao.po.BenefitEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface IBenefitEventDao {

    BenefitEvent findByOrderIdAndEventType(@Param("orderId") String orderId, @Param("eventType") String eventType);

    void insert(BenefitEvent benefitEvent);

    void markPublished(@Param("eventId") String eventId);

    List<BenefitEvent> queryPendingGrants(@Param("eventType") String eventType,
                                          @Param("since") Date since,
                                          @Param("lastId") Long lastId,
                                          @Param("pageSize") int pageSize);

    List<BenefitEvent> queryUnpublished(@Param("limit") int limit);

}
