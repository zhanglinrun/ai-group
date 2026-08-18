package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.api.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayDiscountEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.UserEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.aigroup.groupbuy.trigger.http.MarketTradeController;
import com.aigroup.common.context.RequestUserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MarketTradeTrustedPriceTest {

    @Before
    public void bindUser() {
        RequestUserContext.bind(1L, "u1", "USER");
    }

    @After
    public void clearUser() {
        RequestUserContext.clear();
    }

    @Test
    public void classicGroupKeepsExistingTrialDiscountPath() throws Exception {
        IIndexGroupBuyMarketService marketService = mock(IIndexGroupBuyMarketService.class);
        ITradeLockOrderService tradeService = mock(ITradeLockOrderService.class);
        MarketTradeController controller = new MarketTradeController();
        ReflectionTestUtils.setField(controller, "indexGroupBuyMarketService", marketService);
        ReflectionTestUtils.setField(controller, "tradeOrderService", tradeService);

        when(marketService.indexMarketTrial(any())).thenReturn(TrialBalanceEntity.builder()
                .goodsId("9890002")
                .goodsName("额度包")
                .originalPrice(new BigDecimal("99.00"))
                .deductionPrice(new BigDecimal("10.00"))
                .payPrice(new BigDecimal("89.00"))
                .isVisible(true)
                .isEnable(true)
                .groupBuyActivityDiscountVO(GroupBuyActivityDiscountVO.builder()
                        .activityId(100201L)
                        .activityName("额度拼团")
                        .target(10)
                        .validTime(1440)
                        .build())
                .build());
        when(tradeService.lockMarketPayOrder(any(), any(), any())).thenReturn(MarketPayOrderEntity.builder()
                .teamId("team-1")
                .orderId("group-order-1")
                .originalPrice(new BigDecimal("99.00"))
                .deductionPrice(new BigDecimal("10.00"))
                .payPrice(new BigDecimal("89.00"))
                .tradeOrderStatusEnumVO(TradeOrderStatusEnumVO.CREATE)
                .build());

        LockMarketPayOrderRequestDTO request = new LockMarketPayOrderRequestDTO();
        request.setUserId("1");
        request.setActivityId(100201L);
        request.setGoodsId("9890002");
        request.setOrderPrice(new BigDecimal("12.00"));
        request.setSource("s01");
        request.setChannel("c01");
        request.setOutTradeNo("pay-order-1");
        request.setNotifyMQ();

        Response<LockMarketPayOrderResponseDTO> response = controller.lockMarketPayOrder(request);

        assertEquals("0000", response.getCode());
        ArgumentCaptor<PayDiscountEntity> discount = ArgumentCaptor.forClass(PayDiscountEntity.class);
        verify(tradeService).lockMarketPayOrder(any(UserEntity.class), any(PayActivityEntity.class), discount.capture());
        assertEquals(0, new BigDecimal("99.00").compareTo(discount.getValue().getOriginalPrice()));
        assertEquals(0, new BigDecimal("10.00").compareTo(discount.getValue().getDeductionPrice()));
        assertEquals(0, new BigDecimal("89.00").compareTo(discount.getValue().getPayPrice()));
    }
}
