package com.aigroup.groupbuy.trigger.http;

import com.aigroup.groupbuy.api.IMarketTradeService;
import com.aigroup.groupbuy.api.dto.*;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.model.valobj.GroupBuyProgressVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyConfigVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.NotifyTypeEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.trigger.http.support.GatewayUserBinder;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import com.aigroup.groupbuy.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @description 营销交易服务
 * @create 2025-01-11 14:01
 */
@Slf4j
@RestController()
@RequestMapping("/api/v1/gbm/trade/")
public class MarketTradeController implements IMarketTradeService {

    private static final int IDEMPOTENCY_RECOVERY_ATTEMPTS = 4;
    private static final long IDEMPOTENCY_RECOVERY_BACKOFF_MILLIS = 20L;

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    @Resource
    private ITradeLockOrderService tradeOrderService;
    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;
    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    /**
     * 拼团营销锁单
     */
    @RequestMapping(value = "lock_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO requestDTO) {
        try {
            if (requestDTO == null) {
                return illegalLockRequest();
            }
            requestDTO.setUserId(GatewayUserBinder.requireUserId(requestDTO.getUserId()));
            // 参数
            String userId = requestDTO.getUserId();
            String source = requestDTO.getSource();
            String channel = requestDTO.getChannel();
            String goodsId = requestDTO.getGoodsId();
            Long activityId = requestDTO.getActivityId();
            String outTradeNo = requestDTO.getOutTradeNo();
            String teamId = requestDTO.getTeamId();
            LockMarketPayOrderRequestDTO.NotifyConfigVO notifyConfigVO = requestDTO.getNotifyConfigVO();

            log.info("营销交易锁单:{} LockMarketPayOrderRequestDTO:{}", userId, JsonUtils.toJson(requestDTO));

            if (StringUtils.isBlank(userId) || StringUtils.isBlank(source) || StringUtils.isBlank(channel)
                    || StringUtils.isBlank(goodsId) || null == activityId || StringUtils.isBlank(outTradeNo)
                    || notifyConfigVO == null || StringUtils.isBlank(notifyConfigVO.getNotifyType())
                    || (!("MQ".equals(notifyConfigVO.getNotifyType()) || "HTTP".equals(notifyConfigVO.getNotifyType())))
                    || ("HTTP".equals(notifyConfigVO.getNotifyType()) && StringUtils.isBlank(notifyConfigVO.getNotifyUrl()))) {
                return illegalLockRequest();
            }

            // The full tuple matches uq_sc_out_trade_no. Return the original/current resource for every
            // duplicate state so a retry can never create a second team after an ambiguous response.
            MarketPayOrderEntity marketPayOrderEntity = queryExistingLock(requestDTO);
            if (null != marketPayOrderEntity) {
                log.info("交易锁单记录(存在):{} marketPayOrderEntity:{}", userId, JsonUtils.toJson(marketPayOrderEntity));
                return successfulLockResponse(marketPayOrderEntity);
            }

            // 判断拼团锁单是否完成了目标
            if (StringUtils.isNotBlank(teamId)) {
                GroupBuyProgressVO groupBuyProgressVO = tradeOrderService.queryGroupBuyProgress(teamId);
                if (null != groupBuyProgressVO && Objects.equals(groupBuyProgressVO.getTargetCount(), groupBuyProgressVO.getLockCount())) {
                    log.info("交易锁单拦截-拼单目标已达成:{} {}", userId, teamId);
                    return Response.<LockMarketPayOrderResponseDTO>builder()
                            .code(ResponseCode.E0006.getCode())
                            .info(ResponseCode.E0006.getInfo())
                            .build();
                }
            }

            // 营销优惠试算
            TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(userId)
                    .source(source)
                    .channel(channel)
                    .goodsId(goodsId)
                    .activityId(activityId)
                    .build());

            // 人群限定
            if (!trialBalanceEntity.getIsVisible() || !trialBalanceEntity.getIsEnable()) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.E0007.getCode())
                        .info(ResponseCode.E0007.getInfo())
                        .build();
            }

            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();

            // 额度拼团不做现金折扣。价格由 pay 从启用的 member SKU 查询后透传，
            // group 只负责建团和赠额；缺少可信价格时失败关闭，避免退回本地旧 SKU 价格。
            boolean tieredQuotaActivity = Integer.valueOf(1).equals(groupBuyActivityDiscountVO.getActivityType());
            BigDecimal originalPrice = trialBalanceEntity.getOriginalPrice();
            BigDecimal deductionPrice = trialBalanceEntity.getDeductionPrice();
            BigDecimal payPrice = trialBalanceEntity.getPayPrice();
            if (tieredQuotaActivity) {
                BigDecimal orderPrice = requestDTO.getOrderPrice();
                if (orderPrice == null || orderPrice.signum() <= 0) {
                    return Response.<LockMarketPayOrderResponseDTO>builder()
                            .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                            .info("额度拼团缺少可信订单价格")
                            .build();
                }
                originalPrice = orderPrice;
                deductionPrice = BigDecimal.ZERO;
                payPrice = orderPrice;
            }

            // 营销优惠锁单
            marketPayOrderEntity = tradeOrderService.lockMarketPayOrder(
                    UserEntity.builder().userId(userId).build(),
                    PayActivityEntity.builder()
                            .teamId(teamId)
                            .activityId(activityId)
                            .activityName(groupBuyActivityDiscountVO.getActivityName())
                            .startTime(groupBuyActivityDiscountVO.getStartTime())
                            .endTime(groupBuyActivityDiscountVO.getEndTime())
                            .validTime(groupBuyActivityDiscountVO.getValidTime())
                            .targetCount(groupBuyActivityDiscountVO.getTarget())
                            .build(),
                    PayDiscountEntity.builder()
                            .source(source)
                            .channel(channel)
                            .goodsId(goodsId)
                            .goodsName(trialBalanceEntity.getGoodsName())
                            .originalPrice(originalPrice)
                            .deductionPrice(deductionPrice)
                            .payPrice(payPrice)
                            .outTradeNo(outTradeNo)
                            .notifyConfigVO(
                                    // 构建回调通知对象
                                    NotifyConfigVO.builder()
                                            .notifyType(NotifyTypeEnumVO.valueOf(notifyConfigVO.getNotifyType()))
                                            .notifyMQ(notifyConfigVO.getNotifyMQ())
                                            .notifyUrl(notifyConfigVO.getNotifyUrl())
                                            .build())
                            .build());

            log.info("交易锁单记录(新):{} marketPayOrderEntity:{}", userId, JsonUtils.toJson(marketPayOrderEntity));

            // 返回结果
            return successfulLockResponse(marketPayOrderEntity);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("营销交易锁单拒绝: {}", e.getMessage());
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (AppException e) {
            // Concurrent requests with the same idempotency key can both pass the pre-read. The unique
            // index selects a winner; after the losing transaction rolls back, resolve and return it.
            if (ResponseCode.INDEX_EXCEPTION.getCode().equals(e.getCode())) {
                MarketPayOrderEntity existing = recoverExistingLock(requestDTO);
                if (existing != null) {
                    log.info("交易锁单唯一键竞态已恢复:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());
                    return successfulLockResponse(existing);
                }
            }
            log.error("营销交易锁单业务异常:{} LockMarketPayOrderRequestDTO:{}",
                    requestDTO == null ? null : requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销交易锁单服务失败:{} LockMarketPayOrderRequestDTO:{}",
                    requestDTO == null ? null : requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "query_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> queryMarketPayOrder(@RequestBody QueryMarketPayOrderRequestDTO requestDTO) {
        try {
            if (requestDTO == null) {
                return illegalLockRequest();
            }
            requestDTO.setUserId(GatewayUserBinder.requireUserId(requestDTO.getUserId()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        }
        if (StringUtils.isAnyBlank(requestDTO.getUserId(), requestDTO.getSource(),
                requestDTO.getChannel(), requestDTO.getOutTradeNo())) {
            return illegalLockRequest();
        }

        MarketPayOrderEntity existing = tradeOrderService.queryMarketPayOrderByBusinessKey(
                requestDTO.getUserId(), requestDTO.getSource(), requestDTO.getChannel(), requestDTO.getOutTradeNo());
        if (existing == null) {
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.E0104.getCode())
                    .info(ResponseCode.E0104.getInfo())
                    .build();
        }
        return successfulLockResponse(existing);
    }

    private MarketPayOrderEntity queryExistingLock(LockMarketPayOrderRequestDTO requestDTO) {
        if (requestDTO == null) return null;
        return tradeOrderService.queryMarketPayOrderByBusinessKey(requestDTO.getUserId(), requestDTO.getSource(),
                requestDTO.getChannel(), requestDTO.getOutTradeNo());
    }

    private MarketPayOrderEntity recoverExistingLock(LockMarketPayOrderRequestDTO requestDTO) {
        for (int attempt = 1; attempt <= IDEMPOTENCY_RECOVERY_ATTEMPTS; attempt++) {
            try {
                MarketPayOrderEntity existing = queryExistingLock(requestDTO);
                if (existing != null) return existing;
            } catch (Exception recoveryError) {
                log.warn("交易锁单唯一键竞态查询失败:{} outTradeNo:{} attempt:{}",
                        requestDTO == null ? null : requestDTO.getUserId(),
                        requestDTO == null ? null : requestDTO.getOutTradeNo(), attempt, recoveryError);
            }
            if (attempt < IDEMPOTENCY_RECOVERY_ATTEMPTS && !pauseBeforeRecoveryRetry(attempt)) return null;
        }
        return null;
    }

    private boolean pauseBeforeRecoveryRetry(int attempt) {
        try {
            Thread.sleep(IDEMPOTENCY_RECOVERY_BACKOFF_MILLIS * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Response<LockMarketPayOrderResponseDTO> successfulLockResponse(MarketPayOrderEntity order) {
        return Response.<LockMarketPayOrderResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(LockMarketPayOrderResponseDTO.builder()
                        .orderId(order.getOrderId())
                        .originalPrice(order.getOriginalPrice())
                        .deductionPrice(order.getDeductionPrice())
                        .payPrice(order.getPayPrice())
                        .tradeOrderStatus(order.getTradeOrderStatusEnumVO().getCode())
                        .teamId(order.getTeamId())
                        .build())
                .build();
    }

    private Response<LockMarketPayOrderResponseDTO> illegalLockRequest() {
        return Response.<LockMarketPayOrderResponseDTO>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                .build();
    }

    @RequestMapping(value = "settlement_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(@RequestBody SettlementMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("营销交易组队结算开始:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || null == requestDTO.getOutTradeTime()) {
                return Response.<SettlementMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 结算服务
            TradePaySettlementEntity tradePaySettlementEntity = tradeSettlementOrderService.settlementMarketPayOrder(TradePaySuccessEntity.builder()
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .userId(requestDTO.getUserId())
                    .outTradeNo(requestDTO.getOutTradeNo())
                    .outTradeTime(requestDTO.getOutTradeTime())
                    .build());

            SettlementMarketPayOrderResponseDTO responseDTO = SettlementMarketPayOrderResponseDTO.builder()
                    .userId(tradePaySettlementEntity.getUserId())
                    .teamId(tradePaySettlementEntity.getTeamId())
                    .activityId(tradePaySettlementEntity.getActivityId())
                    .outTradeNo(tradePaySettlementEntity.getOutTradeNo())
                    .build();

            // 返回结果
            Response<SettlementMarketPayOrderResponseDTO> response = Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("营销交易组队结算完成:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JsonUtils.toJson(response));

            return response;
        } catch (AppException e) {
            log.error("营销交易组队结算异常:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销交易组队结算失败:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "refund_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<RefundMarketPayOrderResponseDTO> refundMarketPayOrder(@RequestBody RefundMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("营销拼团退单开始:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel())) {
                return Response.<RefundMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 退单服务
            TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(TradeRefundCommandEntity.builder()
                    .userId(requestDTO.getUserId())
                    .outTradeNo(requestDTO.getOutTradeNo())
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .build());

            RefundMarketPayOrderResponseDTO responseDTO = RefundMarketPayOrderResponseDTO.builder()
                    .userId(tradeRefundBehaviorEntity.getUserId())
                    .orderId(tradeRefundBehaviorEntity.getOrderId())
                    .teamId(tradeRefundBehaviorEntity.getTeamId())
                    .code(tradeRefundBehaviorEntity.getTradeRefundBehaviorEnum().getCode())
                    .info(tradeRefundBehaviorEntity.getTradeRefundBehaviorEnum().getInfo())
                    .build();

            // 返回结果
            Response<RefundMarketPayOrderResponseDTO> response = Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("营销拼团退单完成:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JsonUtils.toJson(response));

            return response;
        } catch (AppException e) {
            log.error("营销拼团退单异常:{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销拼团退单失败:{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JsonUtils.toJson(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
