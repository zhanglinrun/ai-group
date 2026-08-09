package com.aigroup.groupbuy.test.domain.activity;

import com.aigroup.groupbuy.domain.activity.model.valobj.DiscountTypeEnum;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.service.discount.IDiscountCalculateService;
import com.aigroup.groupbuy.domain.activity.service.discount.impl.MJCalculateService;
import com.aigroup.groupbuy.domain.activity.service.discount.impl.MMJCalculateService;
import com.aigroup.groupbuy.domain.activity.service.discount.impl.NCalculateService;
import com.aigroup.groupbuy.domain.activity.service.discount.impl.ZJCalculateService;
import com.aigroup.groupbuy.domain.activity.service.discount.impl.ZKCalculateService;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

/**
 * 折扣计算金额精度回归：ZK 折扣必须保留到分（此前 setScale(0, DOWN) 截断到整数元造成资损），
 * 四种计算器统一输出 2 位小数。用 BASE 折扣类型走非人群标签分支，无需仓储依赖。
 */
public class DiscountCalculateServiceTest {

    private GroupBuyActivityDiscountVO.GroupBuyDiscount discount(String plan, String expr) {
        return GroupBuyActivityDiscountVO.GroupBuyDiscount.builder()
                .discountType(DiscountTypeEnum.BASE)
                .marketPlan(plan)
                .marketExpr(expr)
                .build();
    }

    @Test
    public void zk_keepsCentPrecision_noIntegerTruncation() {
        IDiscountCalculateService service = new ZKCalculateService();
        // 99.90 * 0.85 = 84.915 -> 四舍五入到分 84.92（旧实现 setScale(0,DOWN) 会截断成 84，资损 0.92 元）
        BigDecimal pay = service.calculate("u1", new BigDecimal("99.90"), discount("ZK", "0.85"));
        Assert.assertEquals(0, new BigDecimal("84.92").compareTo(pay));
        Assert.assertEquals(2, pay.scale());
    }

    @Test
    public void zk_15discount_keepsCents() {
        IDiscountCalculateService service = new ZKCalculateService();
        // 100.00 * 0.15 = 15.00
        BigDecimal pay = service.calculate("u1", new BigDecimal("100.00"), discount("ZK", "0.15"));
        Assert.assertEquals(0, new BigDecimal("15.00").compareTo(pay));
    }

    @Test
    public void zj_directSubtract_scale2() {
        IDiscountCalculateService service = new ZJCalculateService();
        // 直减：100.00 - 30 = 70.00
        BigDecimal pay = service.calculate("u1", new BigDecimal("100.00"), discount("ZJ", "30"));
        Assert.assertEquals(0, new BigDecimal("70.00").compareTo(pay));
        Assert.assertEquals(2, pay.scale());
    }

    @Test
    public void mj_thresholdMet_scale2() {
        IDiscountCalculateService service = new MJCalculateService();
        // 满 100 减 10：120.00 - 10 = 110.00
        BigDecimal pay = service.calculate("u1", new BigDecimal("120.00"), discount("MJ", "100,10"));
        Assert.assertEquals(0, new BigDecimal("110.00").compareTo(pay));
        Assert.assertEquals(2, pay.scale());
    }

    @Test
    public void mj_thresholdNotMet_returnsOriginal() {
        IDiscountCalculateService service = new MJCalculateService();
        // 未满 100，原价返回
        BigDecimal pay = service.calculate("u1", new BigDecimal("80.00"), discount("MJ", "100,10"));
        Assert.assertEquals(0, new BigDecimal("80.00").compareTo(pay));
    }

    @Test
    public void n_yuanPurchase_9dot9_scale2() {
        IDiscountCalculateService service = new NCalculateService();
        // 9.9 元购 -> 规范化为 9.90
        BigDecimal pay = service.calculate("u1", new BigDecimal("100.00"), discount("N", "9.9"));
        Assert.assertEquals(0, new BigDecimal("9.90").compareTo(pay));
        Assert.assertEquals(2, pay.scale());
    }

    @Test
    public void zk_floorToOneCentMinimum() {
        IDiscountCalculateService service = new ZKCalculateService();
        // 0.001 * 0.01 极小值 -> 兜底最低 0.01
        BigDecimal pay = service.calculate("u1", new BigDecimal("0.001"), discount("ZK", "0.01"));
        Assert.assertEquals(0, new BigDecimal("0.01").compareTo(pay));
    }

    // ===== MMJ 每满减 =====

    @Test
    public void mmj_basic_twoTimes() {
        IDiscountCalculateService service = new MMJCalculateService();
        // 每满100减10，无次数限制：250 → 满2次 → 250 - 20 = 230
        BigDecimal pay = service.calculate("u1", new BigDecimal("250.00"), discount("MMJ", "100,10,-1"));
        Assert.assertEquals(0, new BigDecimal("230.00").compareTo(pay));
    }

    @Test
    public void mmj_cappedAtMaxTimes() {
        IDiscountCalculateService service = new MMJCalculateService();
        // 每满100减10，最多4次：500 → 满5次但限制4次 → 500 - 40 = 460
        BigDecimal pay = service.calculate("u1", new BigDecimal("500.00"), discount("MMJ", "100,10,4"));
        Assert.assertEquals(0, new BigDecimal("460.00").compareTo(pay));
    }

    @Test
    public void mmj_belowThreshold_returnsOriginal() {
        IDiscountCalculateService service = new MMJCalculateService();
        // 未满100，原价返回
        BigDecimal pay = service.calculate("u1", new BigDecimal("80.00"), discount("MMJ", "100,10,-1"));
        Assert.assertEquals(0, new BigDecimal("80.00").compareTo(pay));
    }

    @Test
    public void mmj_exactlyAtThreshold() {
        IDiscountCalculateService service = new MMJCalculateService();
        // 刚好满100：100 → 满1次 → 100 - 10 = 90
        BigDecimal pay = service.calculate("u1", new BigDecimal("100.00"), discount("MMJ", "100,10,-1"));
        Assert.assertEquals(0, new BigDecimal("90.00").compareTo(pay));
    }

    @Test
    public void mmj_floorToOneCentMinimum() {
        IDiscountCalculateService service = new MMJCalculateService();
        // 每满1减100，10元 → 满10次 → 10 - 1000 = -990 → 兜底 0.01
        BigDecimal pay = service.calculate("u1", new BigDecimal("10.00"), discount("MMJ", "1,100,-1"));
        Assert.assertEquals(0, new BigDecimal("0.01").compareTo(pay));
    }
}
