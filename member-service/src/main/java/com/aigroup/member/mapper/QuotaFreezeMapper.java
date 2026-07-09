package com.aigroup.member.mapper;

import com.aigroup.member.entity.QuotaFreeze;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuotaFreezeMapper extends BaseMapper<QuotaFreeze> {

    @Select("SELECT * FROM quota_freeze WHERE freeze_id = #{freezeId} FOR UPDATE")
    QuotaFreeze selectForUpdateByFreezeId(@Param("freezeId") String freezeId);
}
