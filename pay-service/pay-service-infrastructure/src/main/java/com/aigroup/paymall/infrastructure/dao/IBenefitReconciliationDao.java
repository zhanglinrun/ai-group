package com.aigroup.paymall.infrastructure.dao;

import com.aigroup.paymall.infrastructure.dao.po.BenefitReconciliation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IBenefitReconciliationDao {
    void insertIfAbsent(@Param("eventId") String eventId);

    int claim(@Param("eventId") String eventId, @Param("token") String token);

    BenefitReconciliation findByEventId(@Param("eventId") String eventId);

    int recordReplayIntent(@Param("eventId") String eventId, @Param("token") String token,
                           @Param("maxAttempts") int maxAttempts);

    int finish(@Param("eventId") String eventId, @Param("token") String token,
               @Param("outcome") String outcome, @Param("orderStatus") String orderStatus,
               @Param("memberStatus") String memberStatus, @Param("detail") String detail,
               @Param("delayMinutes") Integer delayMinutes);
}
