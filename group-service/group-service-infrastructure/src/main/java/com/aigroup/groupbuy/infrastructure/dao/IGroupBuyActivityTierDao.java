package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivityTier;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 拼团阶梯档位 Dao
 * @create 2026-07-10
 */
@Mapper
public interface IGroupBuyActivityTierDao {

    /** 按活动ID查询生效档位，按 tier_no 升序 */
    List<GroupBuyActivityTier> queryTiersByActivityId(Long activityId);

    List<GroupBuyActivityTier> queryAllTiersByActivityId(Long activityId);

    int disableTiersByActivityId(Long activityId);

    int upsertTier(GroupBuyActivityTier tier);

}
