package com.aigroup.member.mapper;

import com.aigroup.member.entity.QuotaFreeze;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QuotaFreezeMapper extends BaseMapper<QuotaFreeze> {

    @Select("SELECT * FROM quota_freeze WHERE freeze_id = #{freezeId} FOR UPDATE")
    QuotaFreeze selectForUpdateByFreezeId(@Param("freezeId") String freezeId);

    @Select("SELECT * FROM quota_freeze WHERE user_id = #{userId} AND request_id = #{requestId} LIMIT 1")
    QuotaFreeze selectByUserIdAndRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Select("SELECT * FROM quota_freeze WHERE user_id = #{userId} AND request_id = #{requestId} LIMIT 1 FOR UPDATE")
    QuotaFreeze selectForUpdateByUserIdAndRequestId(@Param("userId") Long userId,
                                                    @Param("requestId") String requestId);

    @Select("SELECT COALESCE(SUM(free_amount), 0) FROM quota_freeze "
            + "WHERE user_id = #{userId} AND status = 'PENDING' FOR UPDATE")
    long sumPendingFreeAmount(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(paid_amount), 0) FROM quota_freeze "
            + "WHERE user_id = #{userId} AND status = 'PENDING' FOR UPDATE")
    long sumPendingPaidAmount(@Param("userId") Long userId);

    /** Scan only legacy/unmanaged PENDING freezes that member may safely release. */
    @Select("SELECT freeze_id FROM quota_freeze WHERE status = 'PENDING' "
            + "AND (owner_service IS NULL OR owner_service <> 'ai-agent') AND created_at < #{cutoff} "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<String> selectExpiredPendingFreezeIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("SELECT freeze_id FROM quota_freeze WHERE status = 'PENDING' "
            + "AND owner_service = 'ai-agent' AND created_at < #{cutoff} "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<String> selectExpiredManagedPendingFreezeIds(@Param("cutoff") LocalDateTime cutoff,
                                                      @Param("limit") int limit);
}
