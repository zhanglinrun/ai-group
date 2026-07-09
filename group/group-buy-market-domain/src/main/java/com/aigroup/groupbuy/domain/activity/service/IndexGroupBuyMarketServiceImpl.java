package com.aigroup.groupbuy.domain.activity.service;

import com.aigroup.groupbuy.domain.activity.adapter.repository.IActivityRepository;
import com.aigroup.groupbuy.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.MarketProductEntity;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.domain.activity.service.trial.factory.DefaultActivityStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 棣栭〉钀ラ攢鏈嶅姟
 * @create 2024-12-14 14:33
 */
@Service
public class IndexGroupBuyMarketServiceImpl implements IIndexGroupBuyMarketService {

    @Resource
    private DefaultActivityStrategyFactory defaultActivityStrategyFactory;
    @Resource
    private IActivityRepository repository;

    @Override
    public TrialBalanceEntity indexMarketTrial(MarketProductEntity marketProductEntity) throws Exception {
        // 鑾峰彇鎵ц绛栫暐
        StrategyHandler<MarketProductEntity, DefaultActivityStrategyFactory.DynamicContext, TrialBalanceEntity> strategyHandler = defaultActivityStrategyFactory.strategyHandler();
        // 鍙楃悊璇曠畻鎿嶄綔
        return strategyHandler.apply(marketProductEntity, new DefaultActivityStrategyFactory.DynamicContext());
    }

    @Override
    public List<UserGroupBuyOrderDetailEntity> queryInProgressUserGroupBuyOrderDetailList(Long activityId, String userId, Integer ownerCount, Integer randomCount) {
        List<UserGroupBuyOrderDetailEntity> unionAllList = new ArrayList<>();

        // 鏌ヨ涓汉鎷煎洟鏁版嵁
        if (0 != ownerCount) {
            List<UserGroupBuyOrderDetailEntity> ownerList = repository.queryInProgressUserGroupBuyOrderDetailListByOwner(activityId, userId, ownerCount);
            if (null != ownerList && !ownerList.isEmpty()){
                unionAllList.addAll(ownerList);
            }
        }

        // 鏌ヨ鍏朵粬闈炰釜浜烘嫾鍥?
        if (0 != randomCount) {
            List<UserGroupBuyOrderDetailEntity> randomList = repository.queryInProgressUserGroupBuyOrderDetailListByRandom(activityId, userId, randomCount);
            if (null != randomList && !randomList.isEmpty()){
                unionAllList.addAll(randomList);
            }
        }

        return unionAllList;
    }

    @Override
    public TeamStatisticVO queryTeamStatisticByActivityId(Long activityId) {
        return repository.queryTeamStatisticByActivityId(activityId);
    }

}
