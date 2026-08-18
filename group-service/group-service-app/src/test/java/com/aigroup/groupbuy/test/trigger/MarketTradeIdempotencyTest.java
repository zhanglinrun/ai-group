package com.aigroup.groupbuy.test.trigger;

import com.aigroup.groupbuy.api.dto.LockMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.dto.LockMarketPayOrderResponseDTO;
import com.aigroup.groupbuy.api.dto.QueryMarketPayOrderRequestDTO;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.domain.activity.model.entity.TrialBalanceEntity;
import com.aigroup.groupbuy.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import com.aigroup.groupbuy.domain.activity.service.IIndexGroupBuyMarketService;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeLockOrderService;
import com.aigroup.groupbuy.trigger.http.MarketTradeController;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import com.aigroup.common.context.RequestUserContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MarketTradeIdempotencyTest {

    private MarketTradeController controller;
    private ITradeLockOrderService tradeService;
    private IIndexGroupBuyMarketService marketService;

    @Before
    public void setUp() {
        RequestUserContext.bind(1L, "u1", "USER");
        controller = new MarketTradeController();
        tradeService = mock(ITradeLockOrderService.class);
        marketService = mock(IIndexGroupBuyMarketService.class);
        ReflectionTestUtils.setField(controller, "tradeOrderService", tradeService);
        ReflectionTestUtils.setField(controller, "indexGroupBuyMarketService", marketService);
    }

    @After
    public void tearDown() {
        RequestUserContext.clear();
    }

    @Test
    public void repeatedLockReturnsOriginalTeamAndCurrentStatus() throws Exception {
        when(tradeService.queryMarketPayOrderByBusinessKey("1", "s01", "c01", "pay-1"))
                .thenReturn(existing(TradeOrderStatusEnumVO.COMPLETE));

        Response<LockMarketPayOrderResponseDTO> response = controller.lockMarketPayOrder(lockRequest());

        assertEquals("0000", response.getCode());
        assertEquals("team-1", response.getData().getTeamId());
        assertEquals(TradeOrderStatusEnumVO.COMPLETE.getCode(), response.getData().getTradeOrderStatus());
        verify(marketService, never()).indexMarketTrial(any());
        verify(tradeService, never()).lockMarketPayOrder(any(), any(), any());
    }

    @Test
    public void queryResultUsesFullDatabaseBusinessKey() {
        when(tradeService.queryMarketPayOrderByBusinessKey("1", "s01", "c01", "pay-1"))
                .thenReturn(existing(TradeOrderStatusEnumVO.CREATE));
        QueryMarketPayOrderRequestDTO request = new QueryMarketPayOrderRequestDTO();
        request.setUserId("1");
        request.setSource("s01");
        request.setChannel("c01");
        request.setOutTradeNo("pay-1");

        Response<LockMarketPayOrderResponseDTO> response = controller.queryMarketPayOrder(request);

        assertEquals("0000", response.getCode());
        assertEquals("group-order-1", response.getData().getOrderId());
        assertEquals("team-1", response.getData().getTeamId());
        verify(tradeService).queryMarketPayOrderByBusinessKey("1", "s01", "c01", "pay-1");
    }

    @Test
    public void uniqueKeyRaceReturnsCommittedWinner() throws Exception {
        when(tradeService.queryMarketPayOrderByBusinessKey("1", "s01", "c01", "pay-1"))
                .thenReturn(null, null, existing(TradeOrderStatusEnumVO.CREATE));
        when(marketService.indexMarketTrial(any())).thenReturn(trial());
        when(tradeService.lockMarketPayOrder(any(), any(), any()))
                .thenThrow(new AppException(ResponseCode.INDEX_EXCEPTION));

        Response<LockMarketPayOrderResponseDTO> response = controller.lockMarketPayOrder(lockRequest());

        assertEquals("0000", response.getCode());
        assertEquals("team-1", response.getData().getTeamId());
        verify(tradeService, times(3))
                .queryMarketPayOrderByBusinessKey("1", "s01", "c01", "pay-1");
    }

    private LockMarketPayOrderRequestDTO lockRequest() {
        LockMarketPayOrderRequestDTO request = new LockMarketPayOrderRequestDTO();
        request.setUserId("1");
        request.setActivityId(100201L);
        request.setGoodsId("9890002");
        request.setOrderPrice(new BigDecimal("12.00"));
        request.setSource("s01");
        request.setChannel("c01");
        request.setOutTradeNo("pay-1");
        request.setNotifyMQ();
        return request;
    }

    private TrialBalanceEntity trial() {
        return TrialBalanceEntity.builder()
                .goodsId("9890002")
                .goodsName("quota package")
                .originalPrice(new BigDecimal("12.00"))
                .deductionPrice(BigDecimal.ZERO)
                .payPrice(new BigDecimal("12.00"))
                .isVisible(true)
                .isEnable(true)
                .groupBuyActivityDiscountVO(GroupBuyActivityDiscountVO.builder()
                        .activityId(100201L)
                        .activityName("quota group")
                        .target(10)
                        .validTime(1440)
                        .build())
                .build();
    }

    private MarketPayOrderEntity existing(TradeOrderStatusEnumVO status) {
        return MarketPayOrderEntity.builder()
                .teamId("team-1")
                .orderId("group-order-1")
                .originalPrice(new BigDecimal("12.00"))
                .deductionPrice(BigDecimal.ZERO)
                .payPrice(new BigDecimal("12.00"))
                .tradeOrderStatusEnumVO(status)
                .build();
    }
}
