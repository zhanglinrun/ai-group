package com.aigroup.groupbuy.domain.trade.service.settlement;

import com.aigroup.groupbuy.domain.trade.adapter.port.ITradePort;
import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyTeamSettlementAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.service.ITradeSettlementOrderService;
import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.aigroup.groupbuy.domain.trade.service.settlement.factory.TradeSettlementRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import com.aigroup.groupbuy.types.exception.AppException;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 鎷煎洟浜ゆ槗缁撶畻鏈嶅姟
 * @create 2025-01-26 15:22
 */
@Slf4j
@Service
public class TradeSettlementOrderService implements ITradeSettlementOrderService {

    @Resource
    private ITradeRepository repository;
    @Resource
    private ITradePort port;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    @Resource
    private ITradeTaskService tradeTaskService;

    @Resource
    private BusinessLinkedList<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> tradeSettlementRuleFilter;

    @Override
    public TradePaySettlementEntity settlementMarketPayOrder(TradePaySuccessEntity tradePaySuccessEntity) throws Exception {
        log.info("鎷煎洟浜ゆ槗-鏀粯璁㈠崟缁撶畻:{} outTradeNo:{}", tradePaySuccessEntity.getUserId(), tradePaySuccessEntity.getOutTradeNo());
        // 1. 缁撶畻瑙勫垯杩囨护
        TradeSettlementRuleFilterBackEntity tradeSettlementRuleFilterBackEntity = tradeSettlementRuleFilter.apply(
                TradeSettlementRuleCommandEntity.builder()
                        .source(tradePaySuccessEntity.getSource())
                        .channel(tradePaySuccessEntity.getChannel())
                        .userId(tradePaySuccessEntity.getUserId())
                        .outTradeNo(tradePaySuccessEntity.getOutTradeNo())
                        .outTradeTime(tradePaySuccessEntity.getOutTradeTime())
                        .build(),
                new TradeSettlementRuleFilterFactory.DynamicContext());

        String teamId = tradeSettlementRuleFilterBackEntity.getTeamId();

        // 2. 鏌ヨ缁勫洟淇℃伅
        GroupBuyTeamEntity groupBuyTeamEntity = GroupBuyTeamEntity.builder()
                .teamId(tradeSettlementRuleFilterBackEntity.getTeamId())
                .activityId(tradeSettlementRuleFilterBackEntity.getActivityId())
                .targetCount(tradeSettlementRuleFilterBackEntity.getTargetCount())
                .completeCount(tradeSettlementRuleFilterBackEntity.getCompleteCount())
                .lockCount(tradeSettlementRuleFilterBackEntity.getLockCount())
                .status(tradeSettlementRuleFilterBackEntity.getStatus())
                .validStartTime(tradeSettlementRuleFilterBackEntity.getValidStartTime())
                .validEndTime(tradeSettlementRuleFilterBackEntity.getValidEndTime())
                .notifyConfigVO(tradeSettlementRuleFilterBackEntity.getNotifyConfigVO())
                .build();

        // 3. 鏋勫缓鑱氬悎瀵硅薄
        GroupBuyTeamSettlementAggregate groupBuyTeamSettlementAggregate = GroupBuyTeamSettlementAggregate.builder()
                .userEntity(UserEntity.builder().userId(tradePaySuccessEntity.getUserId()).build())
                .groupBuyTeamEntity(groupBuyTeamEntity)
                .tradePaySuccessEntity(tradePaySuccessEntity)
                .build();

        // 4. 鎷煎洟浜ゆ槗缁撶畻
        NotifyTaskEntity notifyTaskEntity = repository.settlementMarketPayOrder(groupBuyTeamSettlementAggregate);

        // 5. 缁勯槦鍥炶皟澶勭悊 - 澶勭悊澶辫触涔熶細鏈夊畾鏃朵换鍔¤ˉ鍋匡紝閫氳繃杩欐牱鐨勬柟寮忥紝鍙互鍑忚交浠诲姟璋冨害锛屾彁楂樻椂鏁堟??
        if (null != notifyTaskEntity) {
            threadPoolExecutor.execute(() -> {
                Map<String, Integer> notifyResultMap = null;
                try {
                    notifyResultMap = tradeTaskService.execNotifyJob(notifyTaskEntity);
                    log.info("鍥炶皟閫氱煡鎷煎洟瀹岀粨 result:{}", JSON.toJSONString(notifyResultMap));
                } catch (Exception e) {
                    log.error("鍥炶皟閫氱煡鎷煎洟瀹岀粨澶辫触 result:{}", JSON.toJSONString(notifyResultMap), e);
                    throw new AppException(e.getMessage());
                }
            });
        }

        // 6. 杩斿洖缁撶畻淇℃伅 - 鍏徃涓紑鍙戣繖鏍风殑娴佺▼鏃跺?欙紝浼氭牴鎹閮ㄩ渶瑕佽繘琛屽?肩殑璁剧疆
        return TradePaySettlementEntity.builder()
                .source(tradePaySuccessEntity.getSource())
                .channel(tradePaySuccessEntity.getChannel())
                .userId(tradePaySuccessEntity.getUserId())
                .teamId(teamId)
                .activityId(groupBuyTeamEntity.getActivityId())
                .outTradeNo(tradePaySuccessEntity.getOutTradeNo())
                .build();
    }

}
