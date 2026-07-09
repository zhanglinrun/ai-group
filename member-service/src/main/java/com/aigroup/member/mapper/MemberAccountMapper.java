package com.aigroup.member.mapper;

import com.aigroup.member.entity.MemberAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberAccountMapper extends BaseMapper<MemberAccount> {

    @Select("SELECT * FROM member_account WHERE user_id = #{userId} FOR UPDATE")
    MemberAccount selectForUpdateByUserId(@Param("userId") Long userId);
}
