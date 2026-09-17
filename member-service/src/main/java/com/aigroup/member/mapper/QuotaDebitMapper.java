package com.aigroup.member.mapper;

import com.aigroup.member.entity.QuotaDebit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuotaDebitMapper extends BaseMapper<QuotaDebit> {

    @Select("SELECT * FROM quota_debit WHERE user_id = #{userId} AND request_id = #{requestId} LIMIT 1 FOR UPDATE")
    QuotaDebit selectForUpdateByUserIdAndRequestId(@Param("userId") Long userId,
                                                   @Param("requestId") String requestId);
}
