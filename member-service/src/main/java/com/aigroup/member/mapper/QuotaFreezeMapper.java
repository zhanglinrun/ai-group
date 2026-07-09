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

    /**
     * 扫描超时仍处于 PENDING 的冻结（进程崩溃/发布重启导致 confirm/release 丢失的僵尸冻结），供兜底释放。
     */
    @Select("SELECT freeze_id FROM quota_freeze WHERE status = 'PENDING' AND created_at < #{cutoff} "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<String> selectExpiredPendingFreezeIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
