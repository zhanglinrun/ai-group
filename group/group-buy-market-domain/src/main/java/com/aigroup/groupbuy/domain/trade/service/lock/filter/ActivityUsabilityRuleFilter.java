package com.aigroup.groupbuy.domain.trade.service.lock.filter;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import com.aigroup.groupbuy.types.enums.ActivityStatusEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Date;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 娲诲姩鐨勫彲鐢ㄦ?э紝瑙勫垯杩囨护銆愮姸鎬併?佹湁鏁堟湡銆?
 * @create 2025-01-25 09:18
 */
@Slf4j
@Service
public class ActivityUsabilityRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("浜ゆ槗瑙勫垯杩囨护-娲诲姩鐨勫彲鐢ㄦ?ф牎楠寋} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        // 鏌ヨ鎷煎洟娲诲姩
        GroupBuyActivityEntity groupBuyActivity = repository.queryGroupBuyActivityEntityByActivityId(requestParameter.getActivityId());

        // 鏍￠獙锛涙椿鍔ㄧ姸鎬?- 鍙互鎶涗笟鍔″紓甯竎ode锛屾垨鑰呮妸code鍐欏叆鍒板姩鎬佷笂涓嬫枃dynamicContext涓紝鏈?鍚庤幏鍙栥??
        if (!ActivityStatusEnumVO.EFFECTIVE.equals(groupBuyActivity.getStatus())) {
            log.info("娲诲姩鐨勫彲鐢ㄦ?ф牎楠岋紝闈炵敓鏁堢姸鎬?activityId:{}", requestParameter.getActivityId());
            throw new AppException(ResponseCode.E0101);
        }

        // 鏍￠獙锛涙椿鍔ㄦ椂闂?
        Date currentTime = new Date();
        if (currentTime.before(groupBuyActivity.getStartTime()) || currentTime.after(groupBuyActivity.getEndTime())) {
            log.info("娲诲姩鐨勫彲鐢ㄦ?ф牎楠岋紝闈炲彲鍙備笌鏃堕棿鑼冨洿 activityId:{}", requestParameter.getActivityId());
            throw new AppException(ResponseCode.E0102);
        }

        // 鍐欏叆鍔ㄦ?佷笂涓嬫枃
        dynamicContext.setGroupBuyActivity(groupBuyActivity);

        // 璧板埌涓嬩竴涓矗浠婚摼鑺傜偣
        return next(requestParameter, dynamicContext);
    }

}
