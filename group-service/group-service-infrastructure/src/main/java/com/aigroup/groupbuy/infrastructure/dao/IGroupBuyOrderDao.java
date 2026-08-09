package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
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

    /**
     * 本地演示专用：在当前用户完成真实模拟支付后，补齐剩余演示席位并封团。
     * 生产支付/成团链路仍必须由真实成员逐个完成支付，不调用此方法。
     */
    int finalizeDemoTeam(@Param("teamId") String teamId);

    List<GroupBuyOrder> queryGroupBuyProgressByTeamIds(@Param("teamIds") Set<String> teamIds);

    /**
     * 查询当前用户参与的有效队伍。自己的队伍即使只有锁单成员、尚未完成支付，
     * 也必须在“我的进行中拼团”中展示，方便用户回到订单继续支付。
     */
    List<GroupBuyOrder> queryGroupBuyProgressByTeamIdsForOwner(@Param("teamIds") Set<String> teamIds);

    /** 到期仍在拼单中(PROGRESS)且已有支付成员的团，用于阶梯到期结算 */
    List<GroupBuyOrder> queryExpiredProgressTeams();

    Integer queryAllTeamCount(@Param("teamIds") Set<String> teamIds);

    Integer queryAllTeamCompleteCount(@Param("teamIds") Set<String> teamIds);

    Integer queryAllUserCount(@Param("teamIds") Set<String> teamIds);

    int unpaid2Refund(GroupBuyOrder groupBuyOrderReq);

    int paid2Refund(GroupBuyOrder groupBuyOrderReq);

    int paidTeam2Refund(GroupBuyOrder groupBuyOrderReq);

    int paidTeam2RefundFail(GroupBuyOrder groupBuyOrderReq);

}
