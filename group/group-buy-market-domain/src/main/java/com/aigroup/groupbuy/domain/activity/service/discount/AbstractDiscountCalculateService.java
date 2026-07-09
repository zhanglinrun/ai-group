package com.aigroup.groupbuy.domain.activity.service.discount;

import com.aigroup.groupbuy.domain.activity.adapter.repository.IActivityRepository;
import com.aigroup.groupbuy.domain.activity.model.valobj.DiscountTypeEnum;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.math.BigDecimal;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 鎶樻墸璁＄畻鏈嶅姟鎶借薄绫?
 * @create 2024-12-22 12:32
 */
@Slf4j
public abstract class AbstractDiscountCalculateService implements IDiscountCalculateService {

    @Resource
    protected IActivityRepository repository;

    @Override
    public BigDecimal calculate(String userId, BigDecimal originalPrice, GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount) {
        // 1. 浜虹兢鏍囩杩囨护
        if (DiscountTypeEnum.TAG.equals(groupBuyDiscount.getDiscountType())){
            boolean isCrowdRange = filterTagId(userId, groupBuyDiscount.getTagId());
            if (!isCrowdRange) {
                log.info("鎶樻墸浼樻儬璁＄畻鎷︽埅锛岀敤鎴蜂笉鍐嶄紭鎯犱汉缇ゆ爣绛捐寖鍥村唴 userId:{}", userId);
                return originalPrice;
            }
        }
        // 2. 鎶樻墸浼樻儬璁＄畻
        return doCalculate(originalPrice, groupBuyDiscount);
    }

    // 浜虹兢杩囨护 - 闄愬畾浜虹兢浼樻儬
    private boolean filterTagId(String userId, String tagId) {
        return repository.isTagCrowdRange(tagId, userId);
    }

    protected abstract BigDecimal doCalculate(BigDecimal originalPrice, GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount);

}
