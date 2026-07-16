package com.aigroup.member.mapper;

import com.aigroup.member.entity.QuotaAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuotaAccountMapper extends BaseMapper<QuotaAccount> {

    @Insert("""
            INSERT IGNORE INTO quota_account
                (user_id, free_quota_balance, paid_quota_balance, frozen_balance, last_free_grant_month, update_time)
            VALUES (#{userId}, #{freeQuota}, 0, 0, #{month}, NOW())
            """)
    int insertInitialAccount(@Param("userId") Long userId,
                             @Param("freeQuota") long freeQuota,
                             @Param("month") String month);

    @Select("SELECT * FROM quota_account WHERE user_id = #{userId} FOR UPDATE")
    QuotaAccount selectForUpdateByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE quota_account
            SET frozen_balance = frozen_balance + #{cost},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND free_quota_balance + paid_quota_balance - frozen_balance >= #{cost}
            """)
    int freezeBalanceIfAvailable(@Param("userId") Long userId, @Param("cost") long cost);

    @Update("""
            UPDATE quota_account
            SET frozen_balance = frozen_balance - #{amount},
                update_time = NOW()
            WHERE user_id = #{userId}
              AND frozen_balance >= #{amount}
            """)
    int releaseFrozenBalance(@Param("userId") Long userId, @Param("amount") long amount);
}
