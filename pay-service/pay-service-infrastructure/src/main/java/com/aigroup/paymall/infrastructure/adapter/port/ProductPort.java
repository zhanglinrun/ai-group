package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.adapter.port.MarketSettlementResult;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.*;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import com.aigroup.paymall.types.common.JsonUtils;
import com.aigroup.paymall.types.exception.AppException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.io.IOException;

@Slf4j
@Component
public class ProductPort implements IProductPort {

    @Value("${app.config.group-service.source}")
    private String source;
    @Value("${app.config.group-service.chanel}")
    private String chanel;
    @Value("${app.config.group-service.notify-url}")
    private String notifyUrl;
    @Value("${app.config.group-service.lock-max-attempts:3}")
    private int lockMaxAttempts = 3;
    @Value("${app.config.group-service.lock-retry-backoff-millis:100}")
    private long lockRetryBackoffMillis = 100L;

    private final ProductRPC productRPC;

    private final IGroupBuyMarketService groupBuyMarketService;

    public ProductPort(ProductRPC productRPC, IGroupBuyMarketService groupBuyMarketService) {
        this.productRPC = productRPC;
        this.groupBuyMarketService = groupBuyMarketService;
    }

    @Override
    public ProductEntity queryProductByProductId(String productId) {
        ProductDTO productDTO = productRPC.queryProductByProductId(productId);
        return ProductEntity.builder()
                .productId(productDTO.getProductId())
                .productName(productDTO.getProductName())
                .productDesc(productDTO.getProductDesc())
                .price(productDTO.getPrice())
                .productCode(productDTO.getProductCode())
                .baseQuota(productDTO.getBaseQuota())
                .build();
    }

    @Override
    public MarketPayDiscountEntity lockMarketPayOrder(String userId, String teamId, Long activityId,
                                                      String productId, String orderId, java.math.BigDecimal orderPrice) {
        LockMarketPayOrderRequestDTO requestDTO = new LockMarketPayOrderRequestDTO();
        requestDTO.setUserId(userId);
        requestDTO.setTeamId(teamId);
        requestDTO.setGoodsId(productId);
        requestDTO.setActivityId(activityId);
        requestDTO.setOrderPrice(orderPrice);
        requestDTO.setSource(source);
        requestDTO.setChannel(chanel);
        requestDTO.setOutTradeNo(orderId);
        requestDTO.setNotifyMQ();

        Exception lastFailure = null;
        int attempts = Math.max(1, lockMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Response<LockMarketPayOrderResponseDTO> response =
                        groupBuyMarketService.lockMarketPayOrder(requestDTO);
                log.info("营销锁单{} attempt:{} requestDTO:{} responseDTO:{}", userId, attempt,
                        JsonUtils.toJson(requestDTO), JsonUtils.toJson(response));
                if (response == null) {
                    lastFailure = new IOException("group lock returned an empty body");
                } else if ("0000".equals(response.getCode())) {
                    return toMarketPayDiscount(userId, orderId, response.getData());
                } else if ("0003".equals(response.getCode())) {
                    lastFailure = new IOException("group lock unique-key race");
                } else {
                    log.error("营销锁单业务拒绝 userId:{} orderId:{} code:{} info:{}",
                            userId, orderId, response.getCode(), response.getInfo());
                    // User/activity participation rules are terminal business decisions,
                    // not transient lock failures. Preserve the group service code so the
                    // browser can explain why a second unfinished team cannot be created.
                    if ("E0103".equals(response.getCode())) {
                        throw new AppException(response.getCode(), response.getInfo());
                    }
                    return null;
                }
            } catch (FeignException fe) {
                int status = fe.status();
                if (status >= 500) {
                    lastFailure = new IOException("group lock HTTP " + status, fe);
                } else {
                    log.error("营销锁单 HTTP 拒绝 userId:{} orderId:{} status:{}", userId, orderId, status);
                    return null;
                }
            } catch (AppException e) {
                // Preserve terminal group participation decisions; retry is only for
                // transport/ambiguous failures.
                throw e;
            } catch (Exception e) {
                lastFailure = e instanceof IOException ? (IOException) e : new IOException(e);
                log.warn("营销锁单结果不确定 userId:{} orderId:{} attempt:{}/{} reason:{}",
                        userId, orderId, attempt, attempts, e.getMessage());
            }

            LockMarketPayOrderResponseDTO recovered = queryExistingLock(requestDTO);
            if (recovered != null) {
                log.info("营销锁单通过结果查询恢复 userId:{} orderId:{} attempt:{}", userId, orderId, attempt);
                return toMarketPayDiscount(userId, orderId, recovered);
            }

            if (attempt < attempts && !pauseBeforeLockRetry()) {
                return null;
            }
        }

        log.error("营销锁单恢复耗尽 userId:{} orderId:{} attempts:{}", userId, orderId, attempts, lastFailure);
        return null;
    }

    private LockMarketPayOrderResponseDTO queryExistingLock(LockMarketPayOrderRequestDTO lockRequest) {
        QueryMarketPayOrderRequestDTO query = new QueryMarketPayOrderRequestDTO();
        query.setUserId(lockRequest.getUserId());
        query.setSource(lockRequest.getSource());
        query.setChannel(lockRequest.getChannel());
        query.setOutTradeNo(lockRequest.getOutTradeNo());
        try {
            Response<LockMarketPayOrderResponseDTO> response =
                    groupBuyMarketService.queryMarketPayOrder(query);
            if (response == null || !"0000".equals(response.getCode())) {
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.debug("营销锁单结果查询暂不可用 orderId:{} reason:{}", lockRequest.getOutTradeNo(), e.getMessage());
            return null;
        }
    }

    private MarketPayDiscountEntity toMarketPayDiscount(String userId, String orderId,
                                                         LockMarketPayOrderResponseDTO response) {
        if (response == null || !Integer.valueOf(0).equals(response.getTradeOrderStatus())
                || response.getOriginalPrice() == null || response.getDeductionPrice() == null
                || response.getPayPrice() == null) {
            log.error("营销锁单结果不可用于预支付 userId:{} orderId:{} response:{}",
                    userId, orderId, JsonUtils.toJson(response));
            return null;
        }
        return MarketPayDiscountEntity.builder()
                .originalPrice(response.getOriginalPrice())
                .deductionPrice(response.getDeductionPrice())
                .payPrice(response.getPayPrice())
                .build();
    }

    private boolean pauseBeforeLockRetry() {
        long delay = Math.min(2000L, Math.max(0L, lockRetryBackoffMillis));
        if (delay == 0) return true;
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public MarketSettlementResult settlementMarketPayOrder(String userId, String orderId, Date orderTime) {
        SettlementMarketPayOrderRequestDTO requestDTO = new SettlementMarketPayOrderRequestDTO();
        requestDTO.setSource(source);
        requestDTO.setChannel(chanel);
        requestDTO.setUserId(userId);
        requestDTO.setOutTradeNo(orderId);
        requestDTO.setOutTradeTime(orderTime);

        try {
            Response<SettlementMarketPayOrderResponseDTO> response =
                    groupBuyMarketService.settlementMarketPayOrder(requestDTO);
            log.info("营销结算{} requestDTO:{} responseDTO:{}", userId, JsonUtils.toJson(requestDTO), JsonUtils.toJson(response));
            if (null == response) return MarketSettlementResult.RETRYABLE_FAILURE;

            if ("0000".equals(response.getCode())) {
                return MarketSettlementResult.ACKNOWLEDGED;
            }
            if ("E0104".equals(response.getCode()) || "E0106".equals(response.getCode())
                    || "E0107".equals(response.getCode())) {
                return MarketSettlementResult.TERMINAL_REJECTED;
            }
            return MarketSettlementResult.RETRYABLE_FAILURE;
        } catch (Exception e) {
            log.error("营销结算失败{}", userId, e);
            return MarketSettlementResult.RETRYABLE_FAILURE;
        }
    }

    @Override
    public boolean refundMarketPayOrder(String userId, String orderId) {
        RefundMarketPayOrderRequestDTO requestDTO = new RefundMarketPayOrderRequestDTO();
        requestDTO.setSource(source);
        requestDTO.setChannel(chanel);
        requestDTO.setUserId(userId);
        requestDTO.setOutTradeNo(orderId);

        try {
            Response<RefundMarketPayOrderResponseDTO> response =
                    groupBuyMarketService.refundMarketPayOrder(requestDTO);
            log.info("营销退单{} requestDTO:{} responseDTO:{}", userId, JsonUtils.toJson(requestDTO), JsonUtils.toJson(response));
            if (null == response) return false;

            if (!"0000".equals(response.getCode())) {
                log.error("营销退单失败 userId:{} orderId:{} code:{} info:{}", userId, orderId, response.getCode(), response.getInfo());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("营销退单失败{}", userId, e);
            return false;
        }
    }

}
