package com.aigroup.groupbuy.domain.trade.adapter.port;

import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易接口服务接口
 * @create 2025-01-31 10:38
 */
public interface ITradePort {

    String groupBuyNotify(NotifyTaskEntity notifyTask) throws Exception;

}
