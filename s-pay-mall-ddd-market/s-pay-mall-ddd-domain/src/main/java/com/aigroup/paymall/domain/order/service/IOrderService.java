package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.alipay.api.AlipayApiException;

import java.util.Date;
import java.util.List;

public interface IOrderService {

    PayOrderEntity createOrder(ShopCartEntity shopCartEntity) throws Exception;

    OrderEntity queryOrderByOrderId(String orderId);

    void changeOrderPaySuccess(String orderId, Date orderTime);

    List<String> queryNoPayNotifyOrder();

    List<String> queryTimeoutCloseOrderList();

    boolean changeOrderClose(String orderId);

    /**
     * C2: re-send group settlement for group-buy orders stuck in PAY_SUCCESS
     * (settlement HTTP call was lost); group settlement is idempotent.
     *
     * @return number of orders a settlement retry was sent for
     */
    int compensateMarketSettlement();

    /**
     * WAIT_REFUND 补偿：team_refund 回调丢失时订单会永久卡在 WAIT_REFUND（钱不退）。
     * 扫描滞留单，重发拼团退单通知并直接走支付宝退款兜底（均幂等）。
     *
     * @return 本轮完成退款的订单数
     */
    int compensateWaitRefund();

    void changeOrderMarketSettlement(List<String> outTradeNoList);

    List<OrderEntity> queryUserOrderList(String userId, Long lastId, Integer pageSize);

    /**
     * 营销退单
     */
    boolean refundMarketOrder(String userId, String orderId);

    /**
     * 接收拼团退单消息
     */
    boolean refundPayOrder(String userId, String orderId) throws AlipayApiException;

}
