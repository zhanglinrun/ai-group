package com.aigroup.member.mapper;

import com.aigroup.member.entity.QuotaAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuotaAccountMapper extends BaseMapper<QuotaAccount> {

    @Select("SELECT * FROM quota_account WHERE user_id = #{userId} FOR UPDATE")
    QuotaAccount selectForUpdateByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE quota_account
            SET frozen_balance = frozen_balance + #{cost},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND period_quota_balance + topup_quota_balance - frozen_balance >= #{cost}
            """)
    int freezeBalanceIfAvailable(@Param("userId") Long userId, @Param("cost") int cost);

    @Update("""
            UPDATE quota_account
            SET frozen_balance = frozen_balance - #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND frozen_balance >= #{amount}
            """)
    int releaseFrozenBalance(@Param("userId") Long userId, @Param("amount") int amount);
}
