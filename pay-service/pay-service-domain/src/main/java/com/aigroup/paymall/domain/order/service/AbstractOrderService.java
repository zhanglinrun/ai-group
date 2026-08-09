package com.aigroup.paymall.domain.order.service;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.repository.IOrderRepository;
import com.aigroup.paymall.domain.order.model.aggregate.CreateOrderAggregate;
import com.aigroup.paymall.domain.order.model.entity.*;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderCreateStage;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.alipay.api.AlipayApiException;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public abstract class AbstractOrderService implements IOrderService {

    protected final IOrderRepository repository;

    protected final IProductPort port;

    public AbstractOrderService(IOrderRepository repository, IProductPort port) {
        this.repository = repository;
        this.port = port;
    }

    @Override
    public OrderEntity queryOrderByOrderId(String orderId) {
        return repository.queryOrderByOrderId(orderId);
    }

    @Override
    public PayOrderEntity createOrder(ShopCartEntity shopCartEntity) throws Exception {
        normalizeAndValidate(shopCartEntity);

        // 幂等重放必须只依赖订单快照，不能被后续商品改价/下架破坏。
        OrderEntity existingOrder = repository.queryOrderByClientRequestId(
                shopCartEntity.getUserId(), shopCartEntity.getRequestId());
        if (existingOrder != null) {
            if (shopCartEntity.getProductCode() == null) {
                shopCartEntity.setProductCode(existingOrder.getProductCode());
            }
            String replayFingerprint = OrderRequestFingerprint.calculate(shopCartEntity);
            return resumeOrReplayExisting(shopCartEntity, existingOrder, replayFingerprint);
        }

        // 商品编码、价格和额度全部以服务端目录为准；浏览器 productCode 只用于一致性校验。
        ProductEntity productEntity = port.queryProductByProductId(shopCartEntity.getProductId());
        if (productEntity == null || productEntity.getProductCode() == null || productEntity.getBaseQuota() == null
                || productEntity.getBaseQuota() <= 0) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "quota package is not configured: " + shopCartEntity.getProductId());
        }
        String catalogProductId = normalizeRequired(productEntity.getProductId(), "catalog productId", 16);
        String catalogProductCode = normalizeRequired(productEntity.getProductCode(), "catalog productCode", 64);
        if (shopCartEntity.getProductCode() != null && !catalogProductCode.equals(shopCartEntity.getProductCode())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "product code does not match server catalog");
        }
        productEntity.setProductId(catalogProductId);
        productEntity.setProductCode(catalogProductCode);
        shopCartEntity.setProductId(catalogProductId);
        shopCartEntity.setProductCode(catalogProductCode);
        String requestFingerprint = OrderRequestFingerprint.calculate(shopCartEntity);
        String ownerToken = UUID.randomUUID().toString();

        // 订单实体信息
        OrderEntity orderEntity = CreateOrderAggregate.buildOrderEntity(
                productEntity, shopCartEntity, requestFingerprint, ownerToken);

        // 订单聚合对象
        CreateOrderAggregate orderAggregate = CreateOrderAggregate.builder()
                .userId(shopCartEntity.getUserId())
                .productEntity(productEntity)
                .orderEntity(orderEntity)
                .build();

        // 只有成功插入唯一键的请求拥有后续 group/provider 副作用。并发输家只查询并回放赢家。
        OrderEntity concurrentWinner = this.doSaveOrder(orderAggregate);
        if (concurrentWinner != null) {
            return resumeOrReplayExisting(shopCartEntity, concurrentWinner, requestFingerprint);
        }
        return executeOwnedCreation(shopCartEntity, orderEntity, ownerToken);
    }

    protected abstract OrderEntity doSaveOrder(CreateOrderAggregate orderAggregate);

    protected abstract MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId,
                                                                  String productId, String orderId, BigDecimal orderPrice);

    protected abstract PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount) throws AlipayApiException;

    protected abstract PayOrderEntity doPrepayOrder(String userId, String productId, String productName, String orderId, BigDecimal totalAmount, MarketPayDiscountEntity marketPayDiscountEntity) throws AlipayApiException;

    @Override
    public List<OrderEntity> queryUserOrderList(String userId, Long lastId, Integer pageSize) {
        return repository.queryUserOrderList(userId, lastId, pageSize);
    }

    private PayOrderEntity resumeOrReplayExisting(ShopCartEntity cart, OrderEntity existingOrder,
                                                   String requestFingerprint) throws Exception {
        if (!Objects.equals(existingOrder.getRequestFingerprint(), requestFingerprint)) {
            throw new AppException(ResponseCode.REQUEST_CONFLICT.getCode(),
                    "requestId already exists with a different purchase payload");
        }
        if (!OrderStatusVO.CREATE.equals(existingOrder.getOrderStatusVO())) {
            return replayCompleted(existingOrder);
        }

        OrderCreateStage stage = existingOrder.getCreateStage();
        if (stage == OrderCreateStage.PROVIDER_STARTED || stage == OrderCreateStage.MANUAL_REVIEW
                || stage == OrderCreateStage.PREPAY_READY || stage == null) {
            throw creationReview(existingOrder.getOrderId(), stage, null);
        }

        String ownerToken = UUID.randomUUID().toString();
        if (!repository.claimOrderCreation(existingOrder.getOrderId(), ownerToken)) {
            throw new AppException(ResponseCode.ORDER_CREATION_IN_PROGRESS.getCode(),
                    "order creation is already in progress, orderId:" + existingOrder.getOrderId());
        }
        existingOrder.setCreateOwnerToken(ownerToken);
        return executeOwnedCreation(cart, existingOrder, ownerToken);
    }

    private PayOrderEntity executeOwnedCreation(ShopCartEntity cart, OrderEntity order,
                                                 String ownerToken) throws Exception {
        boolean providerStarted = false;
        try {
            MarketPayDiscountEntity marketPayDiscountEntity = restoreOrLockMarket(cart, order, ownerToken);
            if (!repository.markProviderStarted(order.getOrderId(), ownerToken)) {
                throw new AppException(ResponseCode.ORDER_CREATION_IN_PROGRESS.getCode(),
                        "order creation lease was lost before provider call, orderId:" + order.getOrderId());
            }
            providerStarted = true;

            PayOrderEntity payOrderEntity = doPrepayOrder(cart.getUserId(),
                    order.getProductId(),
                    order.getProductName(),
                    order.getOrderId(),
                    order.getTotalAmount(),
                    marketPayDiscountEntity);
            if (payOrderEntity == null || payOrderEntity.getPayUrl() == null
                    || payOrderEntity.getPayUrl().isBlank()) {
                throw new IllegalStateException("provider returned an empty pay url");
            }
            if (!repository.completeOrderPrepay(payOrderEntity, ownerToken)) {
                markManualReviewBestEffort(order.getOrderId(), ownerToken);
                throw creationReview(order.getOrderId(), OrderCreateStage.PROVIDER_STARTED, null);
            }

            log.info("创建订单-完成，生成支付单。userId:{} requestId:{} orderId:{}",
                    cart.getUserId(), cart.getRequestId(), order.getOrderId());
            return PayOrderEntity.builder()
                    .userId(cart.getUserId())
                    .orderId(order.getOrderId())
                    .payUrl(payOrderEntity.getPayUrl())
                    .orderStatus(payOrderEntity.getOrderStatus())
                    .marketType(payOrderEntity.getMarketType())
                    .marketDeductionAmount(payOrderEntity.getMarketDeductionAmount())
                    .payAmount(payOrderEntity.getPayAmount())
                    .idempotentReplay(false)
                    .build();
        } catch (Exception creationError) {
            if (providerStarted) {
                markManualReviewBestEffort(order.getOrderId(), ownerToken);
                if (creationError instanceof AppException appException
                        && ResponseCode.ORDER_CREATION_REVIEW.getCode().equals(appException.getCode())) {
                    throw appException;
                }
                throw creationReview(order.getOrderId(), OrderCreateStage.PROVIDER_STARTED, creationError);
            }
            // A terminal group participation rejection (for example, the user already
            // has an unfinished team in this activity) must not leave a visible CREATE
            // order that can never reach the payment provider.
            if (creationError instanceof AppException appException
                    && "E0103".equals(appException.getCode())) {
                try {
                    repository.changeOrderClose(order.getOrderId());
                } catch (Exception closeError) {
                    log.warn("failed to close rejected group order orderId:{}", order.getOrderId(), closeError);
                }
            }
            releaseClaimBestEffort(order.getOrderId(), ownerToken);
            throw creationError;
        }
    }

    private MarketPayDiscountEntity restoreOrLockMarket(ShopCartEntity cart, OrderEntity order,
                                                        String ownerToken) {
        if (OrderCreateStage.GROUP_LOCKED.equals(order.getCreateStage())) {
            if (MarketTypeVO.NO_MARKET.getCode().equals(order.getMarketType())) {
                return null;
            }
            return MarketPayDiscountEntity.builder()
                    .originalPrice(order.getTotalAmount())
                    .deductionPrice(order.getMarketDeductionAmount())
                    .payPrice(order.getPayAmount())
                    .build();
        }
        if (!OrderCreateStage.LOCAL_CREATED.equals(order.getCreateStage())) {
            throw creationReview(order.getOrderId(), order.getCreateStage(), null);
        }

        MarketPayDiscountEntity discount = null;
        if (MarketTypeVO.GROUP_BUY_MARKET.equals(cart.getMarketTypeVO())) {
            discount = this.lockMarketPayOrder(cart.getUserId(), cart.getTeamId(), cart.getActivityId(),
                    order.getProductId(), order.getOrderId(), order.getTotalAmount());
            if (discount == null) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(),
                        "group buy lock market pay order failed, orderId:" + order.getOrderId());
            }
        }

        BigDecimal deduction = discount == null ? BigDecimal.ZERO : discount.getDeductionPrice();
        BigDecimal payAmount = discount == null ? order.getTotalAmount() : discount.getPayPrice();
        if (!repository.markGroupLocked(order.getOrderId(), ownerToken, cart.getMarketTypeVO().getCode(),
                deduction, payAmount)) {
            throw new AppException(ResponseCode.ORDER_CREATION_IN_PROGRESS.getCode(),
                    "order creation lease was lost after group lock, orderId:" + order.getOrderId());
        }
        order.setCreateStage(OrderCreateStage.GROUP_LOCKED);
        order.setMarketType(cart.getMarketTypeVO().getCode());
        order.setMarketDeductionAmount(deduction);
        order.setPayAmount(payAmount);
        return discount;
    }

    private PayOrderEntity replayCompleted(OrderEntity existingOrder) {
        log.info("回放幂等支付订单。userId:{} requestId:{} orderId:{} status:{}",
                existingOrder.getUserId(), existingOrder.getClientRequestId(), existingOrder.getOrderId(),
                existingOrder.getOrderStatusVO());
        return PayOrderEntity.builder()
                .userId(existingOrder.getUserId())
                .orderId(existingOrder.getOrderId())
                .payUrl(existingOrder.getPayUrl())
                .orderStatus(existingOrder.getOrderStatusVO())
                .marketType(existingOrder.getMarketType())
                .marketDeductionAmount(existingOrder.getMarketDeductionAmount())
                .payAmount(existingOrder.getPayAmount())
                .idempotentReplay(true)
                .build();
    }

    private AppException creationReview(String orderId, OrderCreateStage stage, Throwable cause) {
        String message = "order creation result requires manual review, orderId:" + orderId + " stage:" + stage;
        return cause == null
                ? new AppException(ResponseCode.ORDER_CREATION_REVIEW.getCode(), message)
                : new AppException(ResponseCode.ORDER_CREATION_REVIEW.getCode(), message, cause);
    }

    private void markManualReviewBestEffort(String orderId, String ownerToken) {
        try {
            repository.markOrderCreationManualReview(orderId, ownerToken);
        } catch (Exception persistenceError) {
            // PROVIDER_STARTED 已在调用前持久化；即使人工态回写失败，后续请求仍会 fail-closed。
            log.error("failed to mark uncertain provider order for manual review orderId:{}", orderId,
                    persistenceError);
        }
    }

    private void releaseClaimBestEffort(String orderId, String ownerToken) {
        try {
            repository.releaseOrderCreationClaim(orderId, ownerToken);
        } catch (Exception persistenceError) {
            // 租约会自然过期，不能让释放失败覆盖原始业务错误。
            log.error("failed to release order creation lease orderId:{}", orderId, persistenceError);
        }
    }

    private void normalizeAndValidate(ShopCartEntity cart) {
        if (cart == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "purchase request is required");
        }
        cart.setRequestId(normalizeRequired(cart.getRequestId(), "requestId", 64));
        if (!cart.getRequestId().matches("[A-Za-z0-9._:-]+")) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "requestId contains unsupported characters");
        }
        cart.setUserId(normalizeRequired(cart.getUserId(), "userId", 32));
        cart.setProductId(normalizeRequired(cart.getProductId(), "productId", 16));
        cart.setProductCode(normalizeOptional(cart.getProductCode(), 64));
        cart.setTeamId(normalizeOptional(cart.getTeamId(), 64));
        if (cart.getMarketTypeVO() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "marketType is required");
        }
        if (MarketTypeVO.NO_MARKET.equals(cart.getMarketTypeVO())) {
            if (cart.getActivityId() != null || cart.getTeamId() != null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "direct purchase cannot carry group activity or team");
            }
            return;
        }
        if (cart.getActivityId() == null || cart.getActivityId() <= 0) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "group purchase requires a positive activityId");
        }
    }

    private static String normalizeRequired(String value, String fieldName, int maxLength) {
        String normalized = normalizeOptional(value, maxLength);
        if (normalized == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), fieldName + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "value exceeds max length " + maxLength);
        }
        return normalized;
    }

}
