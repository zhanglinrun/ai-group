package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 拼团活动Dao
 * @create 2024-12-07 10:10
 */
@Mapper
public interface IGroupBuyActivityDao {

    List<GroupBuyActivity> queryGroupBuyActivityList();

    GroupBuyActivity queryValidGroupBuyActivity(GroupBuyActivity groupBuyActivityReq);

    GroupBuyActivity queryValidGroupBuyActivityId(Long activityId);

    GroupBuyActivity queryGroupBuyActivityByActivityId(Long activityId);

    /** 运营端：更新活动可运营字段（名称/次数限制/目标/时长/状态） */
    int updateGroupBuyActivityConfig(GroupBuyActivity groupBuyActivityReq);

    int insertGroupBuyActivity(GroupBuyActivity groupBuyActivityReq);

}
