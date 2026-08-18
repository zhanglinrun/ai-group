package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @description 用户拼单
 * @create 2025-01-11 10:33
 */
@Mapper
public interface IGroupBuyOrderDao {

    void insert(GroupBuyOrder groupBuyOrder);

    int updateAddLockCount(String teamId);

    int updateSubtractionLockCount(String teamId);

    GroupBuyOrder queryGroupBuyProgress(String teamId);

    GroupBuyOrder queryGroupBuyTeamByTeamId(String teamId);

    GroupBuyOrder queryGroupBuyTeamByTeamIdForUpdate(String teamId);

    List<GroupBuyOrder> queryGroupBuyTeamByTeamIds(@Param("teamIds") Set<String> teamIds);

    int updateAddCompleteCount(String teamId);

    int updateOrderStatus2COMPLETE(String teamId);

    List<GroupBuyOrder> queryGroupBuyProgressByTeamIds(@Param("teamIds") Set<String> teamIds);

    /**
     * 查询当前用户参与的有效队伍。自己的队伍即使只有锁单成员、尚未完成支付，
     * 也必须在“我的进行中拼团”中展示，方便用户回到订单继续支付。
     */
    List<GroupBuyOrder> queryGroupBuyProgressByTeamIdsForOwner(@Param("teamIds") Set<String> teamIds);

    Integer queryAllTeamCount(@Param("teamIds") Set<String> teamIds);

    Integer queryAllTeamCompleteCount(@Param("teamIds") Set<String> teamIds);

    Integer queryAllUserCount(@Param("teamIds") Set<String> teamIds);

    int unpaid2Refund(GroupBuyOrder groupBuyOrderReq);

    int paid2Refund(GroupBuyOrder groupBuyOrderReq);

    int paidTeam2Refund(GroupBuyOrder groupBuyOrderReq);

    int paidTeam2RefundFail(GroupBuyOrder groupBuyOrderReq);

}
