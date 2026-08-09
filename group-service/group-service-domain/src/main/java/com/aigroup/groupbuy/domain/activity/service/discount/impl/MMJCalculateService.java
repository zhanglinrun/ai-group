package com.aigroup.groupbuy.domain.activity.service.discount.impl;

import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.service.discount.AbstractDiscountCalculateService;
import com.aigroup.groupbuy.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 每满减优惠计算（每多次满减）。
 * <p>
 * 表达式格式：
 * <ul>
 *   <li>{@code 100,10,4} — 每满 100 减 10 元，最多减 4 次</li>
 *   <li>{@code 100,10,-1} — 每满 100 减 10 元，无次数限制</li>
 * </ul>
 */
@Slf4j
@Service("MMJ")
public class MMJCalculateService extends AbstractDiscountCalculateService {

    @Override
    protected BigDecimal doCalculate(BigDecimal originalPrice,
                                     GroupBuyActivityDiscountVO.GroupBuyDiscount groupBuyDiscount) {
        log.info("每满多少减多少优惠策略折扣计算:{}", groupBuyDiscount.getDiscountType().getCode());

        String marketExpr = groupBuyDiscount.getMarketExpr();
        String[] split = marketExpr.split(Constants.SPLIT);

        BigDecimal x = new BigDecimal(split[0].trim()); // 满足金额
        BigDecimal y = new BigDecimal(split[1].trim()); // 减免金额

        // 解析次数限制，默认无限制
        int maxTimes = -1;
        if (split.length >= 3) {
            maxTimes = Integer.parseInt(split[2].trim());
        }

        // 不满足最低满减约束，按照原价
        if (originalPrice.compareTo(x) < 0) {
            return originalPrice;
        }

        // 计算满足条件的倍数
        int actualTimes = originalPrice.divide(x, 0, RoundingMode.DOWN).intValue();

        // 应用次数限制
        int finalTimes = (maxTimes > 0) ? Math.min(actualTimes, maxTimes) : actualTimes;

        if (maxTimes > 0) {
            log.info("满减次数限制: 实际满足{}次，限制{}次，最终减免{}次", actualTimes, maxTimes, finalTimes);
        }

        // 总减免金额 = 减免单价 * 最终减免次数
        BigDecimal totalDeduction = y.multiply(new BigDecimal(finalTimes));

        // 最终价格 = 原价 - 总减免金额
        BigDecimal deductionPrice = originalPrice.subtract(totalDeduction);

        return isPriceBelowZero(deductionPrice);
    }
}
