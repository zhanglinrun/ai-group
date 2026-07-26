package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.benefit.service.IBenefitEventService;
import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.types.common.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
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

    /**
     * 沙箱小额真实支付：>0 时实际向支付宝收取 min(该值, 应付价)，页面展示价不变。
     * 本地/演示环境用 0.01 即可完成真实支付闭环；<=0 关闭（按应付价全额收取）。
     */
    @Value("${ai-group.pay.sandbox-amount:0}")
    private BigDecimal sandboxAmount;

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
    protected OrderEntity doSaveOrder(CreateOrderAggregate orderAggregate) {
        return repository.saveOrderIfAbsent(orderAggregate);
    }

    @Override
    protected MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId,
                                                         String productId, String orderId, BigDecimal orderPrice) {
        return port.lockMarketPayOrder(userId, teamId, activityId, productId, orderId, orderPrice);
    }

    @Override
    protected PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount) throws AlipayApiException {
        return doPrepayOrder(userId, productId, productName, orderId, totalAmount, null);
    }

    @Override
    protected PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount, MarketPayDiscountEntity marketPayDiscountEntity) throws AlipayApiException {
        // 应付价：直购=商品价，拼团=折后价（页面展示用，pay_order.total_amount/市场扣减保持真实价格）
        BigDecimal displayAmount = null == marketPayDiscountEntity ? totalAmount : marketPayDiscountEntity.getPayPrice();
        // 实收价：沙箱小额开启时向支付宝只收 min(sandbox, 应付价)。
        // bizContent.total_amount 与持久化 pay_amount 必须同为该值——回调按 pay_amount 核对实付金额，退款也按 pay_amount 原路退。
        BigDecimal payAmount = resolveChargeAmount(displayAmount);

        if (!alipayEnabled) {
            PayOrderEntity payOrderEntity = new PayOrderEntity();
            payOrderEntity.setUserId(userId);
            payOrderEntity.setOrderId(orderId);
            payOrderEntity.setPayUrl("<alipay disabled>");
            payOrderEntity.setOrderStatus(OrderStatusVO.PAY_WAIT);
            payOrderEntity.setMarketType(null == marketPayDiscountEntity ? MarketTypeVO.NO_MARKET.getCode() : MarketTypeVO.GROUP_BUY_MARKET.getCode());
            payOrderEntity.setMarketDeductionAmount(null == marketPayDiscountEntity ? BigDecimal.ZERO : marketPayDiscountEntity.getDeductionPrice());
            payOrderEntity.setPayAmount(payAmount);
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

        // 填充支付订单的营销与金额信息
        payOrderEntity.setMarketType(null == marketPayDiscountEntity ? MarketTypeVO.NO_MARKET.getCode() : MarketTypeVO.GROUP_BUY_MARKET.getCode());
        payOrderEntity.setMarketDeductionAmount(null == marketPayDiscountEntity ? BigDecimal.ZERO : marketPayDiscountEntity.getDeductionPrice());
        payOrderEntity.setPayAmount(payAmount);

        return payOrderEntity;
    }

    /**
     * 计算实际向支付宝收取的金额：沙箱小额未开启（<=0）时按应付价全额；
     * 开启时取 min(sandbox, 应付价)，避免应付价本身低于沙箱额时反而多收。
     */
    private BigDecimal resolveChargeAmount(BigDecimal displayAmount) {
        if (sandboxAmount == null || sandboxAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return displayAmount;
        }
        if (displayAmount == null || displayAmount.compareTo(sandboxAmount) <= 0) {
            return displayAmount;
        }
        return sandboxAmount;
    }

    @Override
    public String prepareTradeQrCode(String orderId) throws AlipayApiException {
        OrderEntity orderEntity = repository.queryOrderByOrderId(orderId);
        if (null == orderEntity) {
            log.warn("prepare qr code skipped, order not found orderId:{}", orderId);
            return null;
        }
        if (!alipayEnabled) {
            log.warn("prepare qr code skipped, alipay disabled orderId:{}", orderId);
            return null;
        }
        // 复用下单时已持久化的实收金额（沙箱小额已在 doPrepayOrder 计算并写入 pay_amount），
        // 保证扫码支付与回调/退款金额一致；缺失时回退到订单总额。
        BigDecimal payAmount = null != orderEntity.getPayAmount() ? orderEntity.getPayAmount() : orderEntity.getTotalAmount();

        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        request.setNotifyUrl(notifyUrl);
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", orderId);
        bizContent.put("total_amount", payAmount);
        bizContent.put("subject", orderEntity.getProductName());
        // 当面付/扫码支付必须显式声明产品码，否则沙箱可能把交易建到错误产品下，扫码时提示"订单不存在"
        bizContent.put("product_code", "FACE_TO_FACE_PAYMENT");
        // 二维码/交易有效期，避免扫码时交易已过期解析失败
        bizContent.put("timeout_express", "30m");
        request.setBizContent(bizContent.toString());

        AlipayTradePrecreateResponse response;
        try {
            response = alipayClient.execute(request);
        } catch (Exception ex) {
            if (!alipayStrict) {
                log.warn("alipay precreate failed, return null (strict=false) orderId:{}", orderId, ex);
                return null;
            }
            if (ex instanceof AlipayApiException apiEx) throw apiEx;
            if (ex instanceof RuntimeException rt) throw rt;
            throw new RuntimeException(ex);
        }
        if (!response.isSuccess()) {
            log.warn("alipay precreate not success orderId:{} code:{} subCode:{} msg:{}",
                    orderId, response.getCode(), response.getSubCode(), response.getSubMsg());
            return null;
        }
        repository.updateOrderPayUrl(orderId, response.getQrCode());
        log.info("alipay precreate ok orderId:{} amount:{} qrCode:{}", orderId, payAmount, response.getQrCode());
        return response.getQrCode();
    }

    /**
     * 取消未支付订单前关闭支付宝侧交易，杜绝"取消后旧收银台仍可付款"。
     * 返回 true 表示可以安全本地关单：支付宝确认关闭、交易根本不存在（CREATE 单从未打开收银台），
     * 或支付宝未启用。返回 false 表示关单未确认（可能被并发支付/网络失败），调用方不得本地关单。
     */
    private boolean closeAlipayTradeIfNeeded(String orderId) {
        if (!alipayEnabled) return true;
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel bizModel = new AlipayTradeCloseModel();
            bizModel.setOutTradeNo(orderId);
            request.setBizModel(bizModel);

            AlipayTradeCloseResponse response = alipayClient.execute(request);
            if (response.isSuccess()) return true;
            // 交易不存在：买家从未打开收银台，无可支付的交易，可安全本地关单
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) return true;
            log.warn("alipay trade close not confirmed orderId:{} code:{} subCode:{}",
                    orderId, response.getCode(), response.getSubCode());
            return false;
        } catch (Exception e) {
            log.warn("alipay trade close failed orderId:{}", orderId, e);
            return false;
        }
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
            // 通知 group 结算（登记本成员已支付)。成功则置结算确认位：补偿任务只重试"通知丢失"(未置位)的单，
            // 正常等待成团的单不再被每分钟重扫，消除错误刷屏与扫描窗口饥饿。
            MarketSettlementResult settlementResult = port.settlementMarketPayOrder(orderEntity.getUserId(), orderId, payTime);
            if (MarketSettlementResult.ACKNOWLEDGED.equals(settlementResult)) {
                repository.markSettlementNotified(orderId);
            } else if (MarketSettlementResult.TERMINAL_REJECTED.equals(settlementResult)) {
                refundTerminalRejectedPayment(orderEntity.getUserId(), orderId);
            }
        } else {
            // Direct-purchase status and both durable outbox rows commit atomically.
            // No MQ call occurs inside this transaction; OutboxEventPublishJob can
            // only observe the rows after commit. Replayed successful callbacks also
            // repair legacy PAY_SUCCESS/DEAL_DONE orders that predate the outbox.
            transactionTemplate.executeWithoutResult(status -> {
                repository.changeOrderPaySuccess(orderId, payTime);
                OrderEntity paidOrder = repository.queryOrderByOrderId(orderId);
                if (isFulfillableDirectOrder(paidOrder)) {
                    benefitEventService.enqueueCompletedOrderEvents(
                            Collections.singletonList(orderId), null);
                } else {
                    log.info("skip direct purchase outbox, order is not paid orderId={} status={}",
                            orderId, paidOrder == null ? null : paidOrder.getOrderStatusVO());
                }
            });
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
                // team_success MQ callback moves it to MARKET, so retrying is safe.
                // 通知成功后置确认位，从后续扫描集合中移除（此处扫到的都是首次通知丢失的单）。
                MarketSettlementResult settlementResult = port.settlementMarketPayOrder(order.getUserId(), order.getOrderId(),
                        null != order.getPayTime() ? order.getPayTime() : new Date());
                if (MarketSettlementResult.ACKNOWLEDGED.equals(settlementResult)) {
                    repository.markSettlementNotified(order.getOrderId());
                    count++;
                } else if (MarketSettlementResult.TERMINAL_REJECTED.equals(settlementResult)) {
                    refundTerminalRejectedPayment(order.getUserId(), order.getOrderId());
                    count++;
                }
            } catch (Exception e) {
                log.error("market settlement compensate failed userId:{} orderId:{}", order.getUserId(), order.getOrderId(), e);
            }
        }
        return count;
    }

    private void refundTerminalRejectedPayment(String userId, String orderId) {
        try {
            if (!refundPayOrder(userId, orderId)) {
                throw new IllegalStateException("terminal group rejection refund was not accepted, orderId=" + orderId);
            }
            log.info("terminal group settlement rejection refunded userId:{} orderId:{}", userId, orderId);
        } catch (AlipayApiException e) {
            throw new IllegalStateException("terminal group rejection refund failed, orderId=" + orderId, e);
        }
    }

    private boolean isFulfillableDirectOrder(OrderEntity order) {
        if (order == null || order.getOrderStatusVO() == null) {
            return false;
        }
        return OrderStatusVO.PAY_SUCCESS.equals(order.getOrderStatusVO())
                || OrderStatusVO.DEAL_DONE.equals(order.getOrderStatusVO());
    }

    @Override
    public int compensateWaitRefund() {
        List<OrderEntity> orders = repository.queryWaitRefundTimeoutOrders();
        if (null == orders || orders.isEmpty()) return 0;

        int count = 0;
        for (OrderEntity order : orders) {
            String userId = order.getUserId();
            String orderId = order.getOrderId();
            // 1. best-effort：重发拼团退单通知，释放 group 组队库存（幂等）
            try {
                port.refundMarketPayOrder(userId, orderId);
            } catch (Exception e) {
                log.warn("wait-refund compensate re-notify group failed userId:{} orderId:{}", userId, orderId, e);
            }
            // 2. 兜底：直接走支付宝退款 + 本地关单（幂等），彻底解除卡单，不依赖 team_refund MQ 再次到达
            try {
                if (refundPayOrder(userId, orderId)) {
                    count++;
                    log.info("wait-refund compensate refunded userId:{} orderId:{}", userId, orderId);
                }
            } catch (Exception e) {
                log.error("wait-refund compensate alipay refund failed userId:{} orderId:{}", userId, orderId, e);
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeOrderMarketSettlement(List<String> outTradeNoList, Integer bonusQuota) {
        // 只对真正从 PAY_SUCCESS 迁移为 MARKET 的订单发放权益；
        // 未支付/已关闭订单即使出现在回调列表里也不发额度。
        List<String> settledOrderIds = repository.changeOrderMarketSettlement(outTradeNoList);
        if (null != settledOrderIds && !settledOrderIds.isEmpty()) {
            // The order transition and both fulfillment/benefit outbox rows share
            // this transaction. The independent publisher sends them after commit.
            benefitEventService.enqueueCompletedOrderEvents(settledOrderIds,
                    bonusQuota == null ? null : bonusQuota.longValue());
        }
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

        // 3. unpaid orders (CREATE/PAY_WAIT): no money moved, close locally.
        //    但 PAY_WAIT 已打开收银台，必须先关闭支付宝侧交易，否则用户从旧收银台仍可付款，
        //    造成"钱已扣、订单已 CLOSE、不发货不退款"。关单未确认（可能并发支付/网络失败）时
        //    不本地关单，交由超时关单 Job 再对账，避免误关已支付单。
        if (OrderStatusVO.CREATE.getCode().equals(status) || OrderStatusVO.PAY_WAIT.getCode().equals(status)) {
            if (!closeAlipayTradeIfNeeded(orderId)) {
                log.warn("cancel aborted, alipay trade close unconfirmed userId:{} orderId:{} status:{}", userId, orderId, status);
                return false;
            }
            if (groupBuyOrder) {
                port.refundMarketPayOrder(userId, orderId);
            }
            return repository.refundOrder(userId, orderId);
        }

        // Paid quota may already have been consumed. User-facing self-service refund is
        // therefore forbidden after payment; failed/expired groups still refund through
        // the internal team_refund callback and refundPayOrder path below.
        log.warn("paid quota order self-refund rejected userId:{} orderId:{} status:{}", userId, orderId, status);
        return false;
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
        // Deterministic idempotency key: if Alipay completed the refund but the
        // response/local transaction was lost, retrying this request returns the
        // same refund result instead of attempting a second refund.
        refundModel.setOutRequestNo("refund-" + orderEntity.getOrderId());
        refundModel.setRefundAmount(orderEntity.getPayAmount().toString());
        refundModel.setRefundReason("\u4ea4\u6613\u9000\u6b3e");
        request.setBizModel(refundModel);

        // 调用支付宝退款接口
        // alipay refund is a long external call, keep it out of the local transaction
        AlipayTradeRefundResponse execute = alipayClient.execute(request);
        if (!execute.isSuccess()) return false;

        // 原子更新本地订单状态并写入权益撤销 outbox；独立发布器在提交后发送。
        // local DB updates (order close + revoke outbox row) commit atomically
        transactionTemplate.executeWithoutResult(status -> {
            repository.refundOrder(userId, orderId);
            benefitEventService.enqueueRevokedBenefitEvents(Collections.singletonList(orderId));
        });

        return true;
    }

}
