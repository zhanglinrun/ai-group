package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.*;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Call;

import java.io.IOException;
import java.util.Date;

@Slf4j
@Component
public class ProductPort implements IProductPort {

    @Value("${app.config.group-buy-market.source}")
    private String source;
    @Value("${app.config.group-buy-market.chanel}")
    private String chanel;
    @Value("${app.config.group-buy-market.notify-url}")
    private String notifyUrl;
    @Value("${app.config.group-buy-market.lock-max-attempts:3}")
    private int lockMaxAttempts = 3;
    @Value("${app.config.group-buy-market.lock-retry-backoff-millis:100}")
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
        // 请求参数
        LockMarketPayOrderRequestDTO requestDTO = new LockMarketPayOrderRequestDTO();
        requestDTO.setUserId(userId);
        requestDTO.setTeamId(teamId);
        requestDTO.setGoodsId(productId);
        requestDTO.setActivityId(activityId);
        requestDTO.setOrderPrice(orderPrice);
        requestDTO.setSource(source);
        requestDTO.setChannel(chanel);
        requestDTO.setOutTradeNo(orderId);
//        requestDTO.setNotifyUrl(notifyUrl);
        requestDTO.setNotifyMQ();

        Exception lastFailure = null;
        int attempts = Math.max(1, lockMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                retrofit2.Response<Response<LockMarketPayOrderResponseDTO>> transport =
                        groupBuyMarketService.lockMarketPayOrder(requestDTO).execute();
                if (!transport.isSuccessful()) {
                    if (transport.code() < 500) {
                        log.error("营销锁单 HTTP 拒绝 userId:{} orderId:{} status:{}", userId, orderId, transport.code());
                        return null;
                    }
                    lastFailure = new IOException("group lock HTTP " + transport.code());
                } else {
                    Response<LockMarketPayOrderResponseDTO> response = transport.body();
                    log.info("营销锁单{} attempt:{} requestDTO:{} responseDTO:{}", userId, attempt,
                            JSON.toJSONString(requestDTO), JSON.toJSONString(response));
                    if (response == null) {
                        lastFailure = new IOException("group lock returned an empty body");
                    } else if ("0000".equals(response.getCode())) {
                        return toMarketPayDiscount(userId, orderId, response.getData());
                    } else if ("0003".equals(response.getCode())) {
                        // A concurrent request may have committed the same unique business key.
                        lastFailure = new IOException("group lock unique-key race");
                    } else {
                        log.error("营销锁单业务拒绝 userId:{} orderId:{} code:{} info:{}",
                                userId, orderId, response.getCode(), response.getInfo());
                        return null;
                    }
                }
            } catch (IOException e) {
                lastFailure = e;
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
            retrofit2.Response<Response<LockMarketPayOrderResponseDTO>> transport =
                    groupBuyMarketService.queryMarketPayOrder(query).execute();
            if (!transport.isSuccessful() || transport.body() == null
                    || !"0000".equals(transport.body().getCode())) {
                return null;
            }
            return transport.body().getData();
        } catch (IOException e) {
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
                    userId, orderId, JSON.toJSONString(response));
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
    public boolean settlementMarketPayOrder(String userId, String orderId, Date orderTime) {
        SettlementMarketPayOrderRequestDTO requestDTO = new SettlementMarketPayOrderRequestDTO();
        requestDTO.setSource(source);
        requestDTO.setChannel(chanel);
        requestDTO.setUserId(userId);
        requestDTO.setOutTradeNo(orderId);
        requestDTO.setOutTradeTime(orderTime);

        try {
            Call<Response<SettlementMarketPayOrderResponseDTO>> call = groupBuyMarketService.settlementMarketPayOrder(requestDTO);

            // 获取结果
            Response<SettlementMarketPayOrderResponseDTO> response = call.execute().body();
            log.info("营销结算{} requestDTO:{} responseDTO:{}", userId, JSON.toJSONString(requestDTO), JSON.toJSONString(response));
            if (null == response) return false;

            // group 已确认登记（0000）才算通知成功；否则返回 false，交补偿任务重试
            return "0000".equals(response.getCode());
        } catch (Exception e) {
            log.error("营销结算失败{}", userId, e);
            return false;
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
            Call<Response<RefundMarketPayOrderResponseDTO>> call = groupBuyMarketService.refundMarketPayOrder(requestDTO);

            // 获取结果
            Response<RefundMarketPayOrderResponseDTO> response = call.execute().body();
            log.info("营销退单{} requestDTO:{} responseDTO:{}", userId, JSON.toJSONString(requestDTO), JSON.toJSONString(response));
            if (null == response) return false;

            // 通知失败如实返回 false（不再吞异常），交调用方/补偿任务处理
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
