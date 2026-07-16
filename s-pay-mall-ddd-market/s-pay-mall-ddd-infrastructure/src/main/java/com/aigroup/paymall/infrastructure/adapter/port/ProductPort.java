package com.aigroup.paymall.infrastructure.adapter.port;

import com.aigroup.paymall.domain.order.adapter.port.IProductPort;
import com.aigroup.paymall.domain.order.model.entity.MarketPayDiscountEntity;
import com.aigroup.paymall.domain.order.model.entity.ProductEntity;
import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.ProductRPC;
import com.aigroup.paymall.infrastructure.gateway.dto.*;
import com.aigroup.paymall.infrastructure.gateway.response.Response;
import com.aigroup.paymall.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Call;

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

        try {
            // 营销锁单
            Call<Response<LockMarketPayOrderResponseDTO>> call = groupBuyMarketService.lockMarketPayOrder(requestDTO);

            // 获取结果
            Response<LockMarketPayOrderResponseDTO> response = call.execute().body();
            log.info("营销锁单{} requestDTO:{} responseDTO:{}", userId, JSON.toJSONString(requestDTO), JSON.toJSONString(response));
            if (null == response) return null;

            // 异常判断
            if (!"0000".equals(response.getCode())) {
                throw new AppException(response.getCode(), response.getInfo());
            }

            LockMarketPayOrderResponseDTO responseDTO = response.getData();

            // 获取拼团优惠
            return MarketPayDiscountEntity.builder()
                    .originalPrice(responseDTO.getOriginalPrice())
                    .deductionPrice(responseDTO.getDeductionPrice())
                    .payPrice(responseDTO.getPayPrice())
                    .build();
        } catch (Exception e) {
            log.error("营销锁单失败{}", userId, e);
            return null;
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
