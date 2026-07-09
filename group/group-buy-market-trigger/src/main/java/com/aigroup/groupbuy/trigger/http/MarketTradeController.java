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
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 钀ラ攢浜ゆ槗鏈嶅姟
 * @create 2025-01-11 14:01
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/trade/")
public class MarketTradeController implements IMarketTradeService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    @Resource
    private ITradeLockOrderService tradeOrderService;
    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;
    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    /**
     * 鎷煎洟钀ラ攢閿佸崟
     */
    @RequestMapping(value = "lock_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO requestDTO) {
        try {
            // 鍙傛暟
            String userId = requestDTO.getUserId();
            String source = requestDTO.getSource();
            String channel = requestDTO.getChannel();
            String goodsId = requestDTO.getGoodsId();
            Long activityId = requestDTO.getActivityId();
            String outTradeNo = requestDTO.getOutTradeNo();
            String teamId = requestDTO.getTeamId();
            LockMarketPayOrderRequestDTO.NotifyConfigVO notifyConfigVO = requestDTO.getNotifyConfigVO();

            log.info("钀ラ攢浜ゆ槗閿佸崟:{} LockMarketPayOrderRequestDTO:{}", userId, JSON.toJSONString(requestDTO));

            if (StringUtils.isBlank(userId) || StringUtils.isBlank(source) || StringUtils.isBlank(channel) || StringUtils.isBlank(goodsId) || null == activityId || ("HTTP".equals(notifyConfigVO.getNotifyType()) && StringUtils.isBlank(notifyConfigVO.getNotifyUrl()))) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 鏌ヨ outTradeNo 鏄惁宸茬粡瀛樺湪浜ゆ槗璁板綍
            MarketPayOrderEntity marketPayOrderEntity = tradeOrderService.queryNoPayMarketPayOrderByOutTradeNo(userId, outTradeNo);
            if (null != marketPayOrderEntity && TradeOrderStatusEnumVO.CREATE.equals(marketPayOrderEntity.getTradeOrderStatusEnumVO())) {
                LockMarketPayOrderResponseDTO lockMarketPayOrderResponseDTO = LockMarketPayOrderResponseDTO.builder()
                        .orderId(marketPayOrderEntity.getOrderId())
                        .originalPrice(marketPayOrderEntity.getOriginalPrice())
                        .deductionPrice(marketPayOrderEntity.getDeductionPrice())
                        .payPrice(marketPayOrderEntity.getPayPrice())
                        .tradeOrderStatus(marketPayOrderEntity.getTradeOrderStatusEnumVO().getCode())
                        .build();

                log.info("浜ゆ槗閿佸崟璁板綍(瀛樺湪):{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(lockMarketPayOrderResponseDTO)
                        .build();
            }

            // 鍒ゆ柇鎷煎洟閿佸崟鏄惁瀹屾垚浜嗙洰鏍?
            if (StringUtils.isNotBlank(teamId)) {
                GroupBuyProgressVO groupBuyProgressVO = tradeOrderService.queryGroupBuyProgress(teamId);
                if (null != groupBuyProgressVO && Objects.equals(groupBuyProgressVO.getTargetCount(), groupBuyProgressVO.getLockCount())) {
                    log.info("浜ゆ槗閿佸崟鎷︽埅-鎷煎崟鐩爣宸茶揪鎴?{} {}", userId, teamId);
                    return Response.<LockMarketPayOrderResponseDTO>builder()
                            .code(ResponseCode.E0006.getCode())
                            .info(ResponseCode.E0006.getInfo())
                            .build();
                }
            }

            // 钀ラ攢浼樻儬璇曠畻
            TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(userId)
                    .source(source)
                    .channel(channel)
                    .goodsId(goodsId)
                    .activityId(activityId)
                    .build());

            // 浜虹兢闄愬畾
            if (!trialBalanceEntity.getIsVisible() || !trialBalanceEntity.getIsEnable()) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.E0007.getCode())
                        .info(ResponseCode.E0007.getInfo())
                        .build();
            }

            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();

            // 钀ラ攢浼樻儬閿佸崟
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
                            .originalPrice(trialBalanceEntity.getOriginalPrice())
                            .deductionPrice(trialBalanceEntity.getDeductionPrice())
                            .payPrice(trialBalanceEntity.getPayPrice())
                            .outTradeNo(outTradeNo)
                            .notifyConfigVO(
                                    // 鏋勫缓鍥炶皟閫氱煡瀵硅薄
                                    NotifyConfigVO.builder()
                                            .notifyType(NotifyTypeEnumVO.valueOf(notifyConfigVO.getNotifyType()))
                                            .notifyMQ(notifyConfigVO.getNotifyMQ())
                                            .notifyUrl(notifyConfigVO.getNotifyUrl())
                                            .build())
                            .build());

            log.info("浜ゆ槗閿佸崟璁板綍(鏂?:{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));

            // 杩斿洖缁撴灉
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(LockMarketPayOrderResponseDTO.builder()
                            .orderId(marketPayOrderEntity.getOrderId())
                            .originalPrice(marketPayOrderEntity.getOriginalPrice())
                            .deductionPrice(marketPayOrderEntity.getDeductionPrice())
                            .payPrice(marketPayOrderEntity.getPayPrice())
                            .tradeOrderStatus(marketPayOrderEntity.getTradeOrderStatusEnumVO().getCode())
                            .teamId(marketPayOrderEntity.getTeamId())
                            .build())
                    .build();
        } catch (AppException e) {
            log.error("钀ラ攢浜ゆ槗閿佸崟涓氬姟寮傚父:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("钀ラ攢浜ゆ槗閿佸崟鏈嶅姟澶辫触:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "settlement_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(@RequestBody SettlementMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("钀ラ攢浜ゆ槗缁勯槦缁撶畻寮?濮?{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || null == requestDTO.getOutTradeTime()) {
                return Response.<SettlementMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 缁撶畻鏈嶅姟
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

            // 杩斿洖缁撴灉
            Response<SettlementMarketPayOrderResponseDTO> response = Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("钀ラ攢浜ゆ槗缁勯槦缁撶畻瀹屾垚:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JSON.toJSONString(response));

            return response;
        } catch (AppException e) {
            log.error("钀ラ攢浜ゆ槗缁勯槦缁撶畻寮傚父:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("钀ラ攢浜ゆ槗缁勯槦缁撶畻澶辫触:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
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
            log.info("钀ラ攢鎷煎洟閫?鍗曞紑濮?{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel())) {
                return Response.<RefundMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 閫?鍗曟湇鍔?
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

            // 杩斿洖缁撴灉
            Response<RefundMarketPayOrderResponseDTO> response = Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("钀ラ攢鎷煎洟閫?鍗曞畬鎴?{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JSON.toJSONString(response));

            return response;
        } catch (AppException e) {
            log.error("钀ラ攢鎷煎洟閫?鍗曞紓甯?{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("钀ラ攢鎷煎洟閫?鍗曞け璐?{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
