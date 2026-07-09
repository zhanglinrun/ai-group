package com.aigroup.groupbuy.domain.trade.service.refund.business;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;
import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.aigroup.groupbuy.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 閫?鍗曠瓥鐣ユ娊璞″熀绫?
 * 鎻愪緵鍏辩敤鐨勪緷璧栨敞鍏ュ拰MQ娑堟伅鍙戦?佸姛鑳?
 *
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * @create 2025-01-01 00:00
 */
@Slf4j
public abstract class AbstractRefundOrderStrategy implements IRefundOrderStrategy {

    @Resource
    protected ITradeRepository repository;

    @Resource
    protected ITradeTaskService tradeTaskService;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    /**
     * 寮傛鍙戦?丮Q娑堟伅
     * @param notifyTaskEntity 閫氱煡浠诲姟瀹炰綋
     * @param refundType 閫?鍗曠被鍨嬫弿杩?
     */
    protected void sendRefundNotifyMessage(NotifyTaskEntity notifyTaskEntity, String refundType) {
        if (null != notifyTaskEntity) {
            threadPoolExecutor.execute(() -> {
                Map<String, Integer> notifyResultMap = null;
                try {
                    notifyResultMap = tradeTaskService.execNotifyJob(notifyTaskEntity);
                    log.info("鍥炶皟閫氱煡浜ゆ槗閫?鍗?{}) result:{}", refundType, JSON.toJSONString(notifyResultMap));
                } catch (Exception e) {
                    log.error("鍥炶皟閫氱煡浜ゆ槗閫?鍗曞け璐?{}) result:{}", refundType, JSON.toJSONString(notifyResultMap), e);
                    throw new AppException(e.getMessage());
                }
            });
        }
    }

    /**
     * 閫氱敤搴撳瓨鎭㈠閫昏緫
     * @param teamRefundSuccess 鍥㈤槦閫?鍗曟垚鍔熶俊鎭?
     * @param refundType 閫?鍗曠被鍨嬫弿杩?
     * @throws Exception 寮傚父
     */
    protected void doReverseStock(TeamRefundSuccess teamRefundSuccess, String refundType) throws Exception {
        log.info("閫?鍗曪紱鎭㈠閿佸崟閲?- {} {} {} {}", refundType, teamRefundSuccess.getUserId(), teamRefundSuccess.getActivityId(), teamRefundSuccess.getTeamId());
        // 1. 鎭㈠搴撳瓨key
        String recoveryTeamStockKey = TradeLockRuleFilterFactory.generateRecoveryTeamStockKey(teamRefundSuccess.getActivityId(), teamRefundSuccess.getTeamId());
        // 2. 閫?鍗曟仮澶嶅簱瀛?
        repository.refund2AddRecovery(recoveryTeamStockKey, teamRefundSuccess.getOrderId());
    }

}