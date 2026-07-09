package com.aigroup.paymall.infrastructure.dao;

import com.aigroup.paymall.infrastructure.dao.po.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IOrderDao {

    void insert(PayOrder payOrder);

    PayOrder queryUnPayOrder(PayOrder payOrder);

    void updateOrderPayInfo(PayOrder payOrder);

    int changeOrderPaySuccess(PayOrder payOrderReq);

    List<String> queryNoPayNotifyOrder();

    List<String> queryTimeoutCloseOrderList();

    List<PayOrder> queryPaySuccessMarketTimeoutOrderList();

    List<PayOrder> queryWaitRefundTimeoutOrderList();

    int markSettlementNotified(String orderId);

    boolean changeOrderClose(String orderId);

    void changeOrderMarketSettlement(@Param("outTradeNoList") List<String> outTradeNoList);

    PayOrder queryOrderByOrderId(String orderId);

    void changeOrderDealDone(String orderId);

    List<PayOrder> queryUserOrderList(@Param("userId") String userId, @Param("lastId") Long lastId, @Param("pageSize") Integer pageSize);

    PayOrder queryOrderByUserIdAndOrderId(@Param("userId") String userId, @Param("orderId") String orderId);

    boolean refundOrder(@Param("userId") String userId, @Param("orderId") String orderId);

    boolean refundMarketOrder(@Param("userId") String userId, @Param("orderId") String orderId);

}
