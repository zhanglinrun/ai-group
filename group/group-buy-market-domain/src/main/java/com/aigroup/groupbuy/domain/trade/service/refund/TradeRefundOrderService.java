package com.aigroup.groupbuy.domain.trade.service.refund;

import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.*;
import com.aigroup.groupbuy.domain.trade.model.valobj.RefundTypeEnumVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.TaskNotifyCategoryEnumVO;
import com.aigroup.groupbuy.domain.trade.model.valobj.TeamRefundSuccess;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeRefundOrderService;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.aigroup.groupbuy.domain.trade.service.refund.business.IRefundOrderStrategy;
import com.aigroup.groupbuy.domain.trade.service.refund.factory.TradeRefundRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 閫?鍗曪紝閫嗗悜娴佺▼鏈嶅姟
 *
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * 2025/7/8 07:27
 */
@Slf4j
@Service
public class TradeRefundOrderService implements ITradeRefundOrderService {

    @Resource
    private BusinessLinkedList<TradeRefundCommandEntity, TradeRefundRuleFilterFactory.DynamicContext, TradeRefundBehaviorEntity> tradeRefundRuleFilter;

    private final ITradeRepository repository;

    private final Map<String, IRefundOrderStrategy> refundOrderStrategyMap;

    public TradeRefundOrderService(ITradeRepository repository, Map<String, IRefundOrderStrategy> refundOrderStrategyMap) {
        this.repository = repository;
        this.refundOrderStrategyMap = refundOrderStrategyMap;
    }

    @Override
    public TradeRefundBehaviorEntity refundOrder(TradeRefundCommandEntity tradeRefundCommandEntity) throws Exception {
        log.info("閫嗗悜娴佺▼锛岄??鍗曟搷浣?userId:{} outTradeNo:{}", tradeRefundCommandEntity.getUserId(), tradeRefundCommandEntity.getOutTradeNo());
        return tradeRefundRuleFilter.apply(tradeRefundCommandEntity, new TradeRefundRuleFilterFactory.DynamicContext());
    }

    @Override
    public void restoreTeamLockStock(TeamRefundSuccess teamRefundSuccess) throws Exception {
        log.info("閫嗗悜娴佺▼锛屾仮澶嶉攣鍗曢噺 userId:{} activityId:{} teamId:{}", teamRefundSuccess.getUserId(), teamRefundSuccess.getActivityId(), teamRefundSuccess.getTeamId());
        String type = teamRefundSuccess.getType();

        // 鏍规嵁鏋氫妇鍊艰幏鍙栧搴旂殑閫?鍗曠被鍨?
        RefundTypeEnumVO refundTypeEnumVO = RefundTypeEnumVO.getRefundTypeEnumVOByCode(type);
        IRefundOrderStrategy refundOrderStrategy = refundOrderStrategyMap.get(refundTypeEnumVO.getStrategy());

        // 閫嗗悜搴撳瓨鎿嶄綔锛屾仮澶嶉攣鍗曢噺
        refundOrderStrategy.reverseStock(teamRefundSuccess);
    }

    @Override
    public List<UserGroupBuyOrderDetailEntity> queryTimeoutUnpaidOrderList() {
        log.info("鎵弿鏁版嵁锛岃秴鏃剁粍闃熸湭鏀粯璁㈠崟");
        return repository.queryTimeoutUnpaidOrderList();
    }

    @Override
    public List<UserGroupBuyOrderDetailEntity> queryTimeoutPaidUnformedOrderList() {
        log.info("scan timeout paid-but-unformed group-buy orders");
        return repository.queryTimeoutPaidUnformedOrderList();
    }

}
