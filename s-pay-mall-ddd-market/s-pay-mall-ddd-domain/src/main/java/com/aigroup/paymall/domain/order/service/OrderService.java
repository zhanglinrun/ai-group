package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class OrderService extends AbstractOrderService {

    @Value("${alipay.enabled:true}")
    private boolean alipayEnabled;

    @Value("${alipay.strict:true}")
    private boolean alipayStrict;

    @Value("${alipay.notify_url}")
    private String notifyUrl;
    @Value("${alipay.return_url}")
    private String returnUrl;

    @Resource
    private AlipayClient alipayClient;
    @Resource
    private IBenefitEventService benefitEventService;
    @Resource
    private TransactionTemplate transactionTemplate;

    public OrderService(IOrderRepository repository, IProductPort port) {
        super(repository, port);
    }

    @Override
    protected void doSaveOrder(CreateOrderAggregate orderAggregate) {
        repository.doSaveOrder(orderAggregate);
    }

    @Override
    protected MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId, String productId, String orderId) {
        return port.lockMarketPayOrder(userId, teamId, activityId, productId, orderId);
    }

    @Override
    protected PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount) throws AlipayApiException {
        return doPrepayOrder(userId, productId, productName, orderId, totalAmount, null);
    }

    @Override
    protected PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount, MarketPayDiscountEntity marketPayDiscountEntity) throws AlipayApiException {
        // ????
        BigDecimal payAmount = null == marketPayDiscountEntity ? totalAmount : marketPayDiscountEntity.getPayPrice();

        if (!alipayEnabled) {
            PayOrderEntity payOrderEntity = new PayOrderEntity();
            payOrderEntity.setUserId(userId);
            payOrderEntity.setOrderId(orderId);
            payOrderEntity.setPayUrl("<alipay disabled>");
            payOrderEntity.setOrderStatus(OrderStatusVO.PAY_WAIT);
            payOrderEntity.setMarketType(null == marketPayDiscountEntity ? MarketTypeVO.NO_MARKET.getCode() : MarketTypeVO.GROUP_BUY_MARKET.getCode());
            payOrderEntity.setMarketDeductionAmount(null == marketPayDiscountEntity ? BigDecimal.ZERO : marketPayDiscountEntity.getDeductionPrice());
            payOrderEntity.setPayAmount(payAmount);
            repository.updateOrderPayInfo(payOrderEntity);
            return payOrderEntity;
        }

        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(returnUrl);

        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", orderId);
        bizContent.put("total_amount", payAmount);
        bizContent.put("subject", productName);
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        request.setBizContent(bizContent.toString());

        String form;
        try {
            form = alipayClient.pageExecute(request).getBody();
        } catch (Exception ex) {
            boolean missingKey = containsMissingKeyHint(ex);
            if (missingKey || !alipayStrict) {
                log.warn("alipay pageExecute failed, falling back to stub payUrl (strict=false) orderId={}", orderId, ex);
                form = "<alipay unavailable>";
            } else {
                if (ex instanceof AlipayApiException apiEx) {
                    throw apiEx;
                }
                if (ex instanceof RuntimeException rt) {
                    throw rt;
                }
                throw new RuntimeException(ex);
            }
        }

        PayOrderEntity payOrderEntity = new PayOrderEntity();
        payOrderEntity.setOrderId(orderId);
        payOrderEntity.setPayUrl(form);
        payOrderEntity.setOrderStatus(OrderStatusVO.PAY_WAIT);

        // ????
        payOrderEntity.setMarketType(null == marketPayDiscountEntity ? MarketTypeVO.NO_MARKET.getCode() : MarketTypeVO.GROUP_BUY_MARKET.getCode());
        payOrderEntity.setMarketDeductionAmount(null == marketPayDiscountEntity ? BigDecimal.ZERO : marketPayDiscountEntity.getDeductionPrice());
        payOrderEntity.setPayAmount(payAmount);

        repository.updateOrderPayInfo(payOrderEntity);

        return payOrderEntity;
    }

    private boolean containsMissingKeyHint(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 8) {
            String msg = current.getMessage();
            if (msg != null && (msg.contains("privateKey") || msg.contains("私钥"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public void changeOrderPaySuccess(String orderId, Date payTime) {
        OrderEntity orderEntity = repository.queryOrderByOrderId(orderId);
        if (null == orderEntity) return;

        if (MarketTypeVO.GROUP_BUY_MARKET.getCode().equals(orderEntity.getMarketType())) {
            repository.changeMarketOrderPaySuccess(orderId);
            // ??????????????http/rpc??????????????????????????????????????
            port.settlementMarketPayOrder(orderEntity.getUserId(), orderId, payTime);
            // ?????????????http/rpc?????????????????job???????????????????????n???????????????????
            // ?????????????????????job????????????????job?????
        } else {
            repository.changeOrderPaySuccess(orderId, payTime);
        }

    }

    @Override
    public List<String> queryNoPayNotifyOrder() {
        return repository.queryNoPayNotifyOrder();
    }

    @Override
    public List<String> queryTimeoutCloseOrderList() {
        return repository.queryTimeoutCloseOrderList();
    }

    @Override
    public boolean changeOrderClose(String orderId) {
        return repository.changeOrderClose(orderId);
    }

    @Override
    public int compensateMarketSettlement() {
        List<OrderEntity> orders = repository.queryPaySuccessMarketTimeoutOrders();
        if (null == orders || orders.isEmpty()) return 0;

        int count = 0;
        for (OrderEntity order : orders) {
            try {
                // group settlement is idempotent; order stays PAY_SUCCESS until the
                // team_success MQ callback moves it to MARKET, so retrying is safe
                port.settlementMarketPayOrder(order.getUserId(), order.getOrderId(),
                        null != order.getPayTime() ? order.getPayTime() : new Date());
                count++;
            } catch (Exception e) {
                log.error("market settlement compensate failed userId:{} orderId:{}", order.getUserId(), order.getOrderId(), e);
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeOrderMarketSettlement(List<String> outTradeNoList) {
        repository.changeOrderMarketSettlement(outTradeNoList);
        benefitEventService.publishGroupBuyCompletedEvents(outTradeNoList);
    }

    @Override
    public boolean refundMarketOrder(String userId, String orderId) {
        // 1. query and validate the order
        OrderEntity orderEntity = repository.queryOrderByUserIdAndOrderId(userId, orderId);
        if (null == orderEntity) {
            log.warn("refund order not found userId:{} orderId:{}", userId, orderId);
            return false;
        }

        // 2. closed orders can not be refunded again
        String status = orderEntity.getOrderStatusVO().getCode();
        if (OrderStatusVO.CLOSE.getCode().equals(status)) {
            log.warn("refund rejected, order already closed userId:{} orderId:{} status:{}", userId, orderId, status);
            return false;
        }

        boolean groupBuyOrder = MarketTypeVO.GROUP_BUY_MARKET.getCode().equals(orderEntity.getMarketType());

        // 3. unpaid orders (CREATE/PAY_WAIT): no money moved, close locally;
        //    group lock-stock release only applies to group-buy orders
        if (OrderStatusVO.CREATE.getCode().equals(status) || OrderStatusVO.PAY_WAIT.getCode().equals(status)) {
            if (groupBuyOrder) {
                port.refundMarketPayOrder(userId, orderId);
            }
            return repository.refundOrder(userId, orderId);
        }

        // 4. paid group-buy order: notify group, park in WAIT_REFUND and wait for
        //    the team_refund MQ callback to execute the alipay refund
        if (groupBuyOrder) {
            port.refundMarketPayOrder(userId, orderId);
            boolean result = repository.refundMarketOrder(userId, orderId);
            if (result) {
                log.info("group-buy refund accepted, waiting refund callback userId:{} orderId:{}", userId, orderId);
            } else {
                log.warn("group-buy refund not applied userId:{} orderId:{} status:{}", userId, orderId, status);
            }
            return result;
        }

        // 5. C4: paid NO_MARKET order has no group record, the team_refund message
        //    would never arrive - refund directly through alipay and close locally
        //    instead of parking it in WAIT_REFUND forever
        try {
            return refundPayOrder(userId, orderId);
        } catch (AlipayApiException e) {
            log.error("no-market direct refund failed userId:{} orderId:{}", userId, orderId, e);
            return false;
        }
    }

    @Override
    public boolean refundPayOrder(String userId, String orderId) throws AlipayApiException {
        // 1. query and validate the order
        OrderEntity orderEntity = repository.queryOrderByUserIdAndOrderId(userId, orderId);
        if (null == orderEntity) {
            log.warn("refund pay order not found userId:{} orderId:{}", userId, orderId);
            return false;
        }

        // idempotency guard: CLOSE means already refunded/closed - report success so a
        // redelivered team_refund message is acked instead of dead-lettered (C3/C5)
        if (OrderStatusVO.CLOSE.getCode().equals(orderEntity.getOrderStatusVO().getCode())) {
            log.info("refund pay order skipped, already closed userId:{} orderId:{}", userId, orderId);
            return true;
        }

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        AlipayTradeRefundModel refundModel = new AlipayTradeRefundModel();
        refundModel.setOutTradeNo(orderEntity.getOrderId());
        refundModel.setRefundAmount(orderEntity.getPayAmount().toString());
        refundModel.setRefundReason("\u4ea4\u6613\u9000\u6b3e");
        request.setBizModel(refundModel);

        // ?????
        // alipay refund is a long external call, keep it out of the local transaction
        AlipayTradeRefundResponse execute = alipayClient.execute(request);
        if (!execute.isSuccess()) return false;

        // ?????
        // local DB updates (order close + revoke benefit event) committed atomically
        transactionTemplate.executeWithoutResult(status -> {
            repository.refundOrder(userId, orderId);
            benefitEventService.publishGroupBuyRevokedEvents(Collections.singletonList(orderId));
        });

        return true;
    }

}
