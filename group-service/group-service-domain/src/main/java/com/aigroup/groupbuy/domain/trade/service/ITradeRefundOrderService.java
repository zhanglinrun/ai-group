package com.aigroup.groupbuy.domain.trade.service;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundBehaviorEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeRefundCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;

import java.util.List;

/**
 * 退单，逆向流程接口
 *
 * 2025/7/8 07:24
 */
public interface ITradeRefundOrderService {

    TradeRefundBehaviorEntity refundOrder(TradeRefundCommandEntity tradeRefundCommandEntity) throws Exception;

    /**
     * 退单恢复锁单库存
     * @param teamRefundSuccess 退单消息
     * @throws Exception 异常
     */
    void restoreTeamLockStock(TeamRefundSuccess teamRefundSuccess) throws Exception;

    /**
     * 查询超时未支付订单列表
     * 条件：当前时间不在活动时间范围内、状态为0（初始锁定）、out_trade_time为空
     * @return 超时未支付订单列表，限制10条
     */
    List<UserGroupBuyOrderDetailEntity> queryTimeoutUnpaidOrderList();

    /**
     * 查询已支付但拼团超时未成团的订单列表（团仍 PROGRESS 且窗口已过），用于自动退款闭环。
     * @return 已支付未成团超时订单列表，限制10条
     */
    List<UserGroupBuyOrderDetailEntity> queryTimeoutPaidUnformedOrderList();

}
