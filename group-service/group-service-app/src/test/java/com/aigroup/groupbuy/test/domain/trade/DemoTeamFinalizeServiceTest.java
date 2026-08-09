package com.aigroup.groupbuy.test.domain.trade;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.GroupBuyTeamEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.aigroup.groupbuy.domain.trade.service.settlement.TradeSettlementOrderService;
import com.aigroup.groupbuy.types.enums.GroupBuyOrderEnumVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import com.aigroup.groupbuy.types.exception.AppException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DemoTeamFinalizeServiceTest {

    private ITradeRepository repository;
    private ITradeTaskService taskService;
    private TradeSettlementOrderService service;

    @Before
    public void setUp() {
        repository = mock(ITradeRepository.class);
        taskService = mock(ITradeTaskService.class);
        service = new TradeSettlementOrderService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "tradeTaskService", taskService);
    }

    @Test
    public void unpaidMemberOrderCannotFinalizeTeam() throws Exception {
        when(repository.queryMarketPayOrderEntityByOutTradeNo("u1", "o1"))
                .thenReturn(memberOrder(TradeOrderStatusEnumVO.CREATE));

        try {
            service.finalizePaidTeamForDemo("u1", "o1");
            fail("expected unpaid order rejection");
        } catch (AppException e) {
            assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getCode());
        }
        verify(repository, never()).finalizePaidTeamForDemo(any(), any());
    }

    @Test
    public void paidOrderFinalizesRealTeamAndPublishesOriginalTask() throws Exception {
        when(repository.queryMarketPayOrderEntityByOutTradeNo("u1", "o1"))
                .thenReturn(memberOrder(TradeOrderStatusEnumVO.COMPLETE));
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(GroupBuyOrderEnumVO.PROGRESS));
        NotifyTaskEntity task = NotifyTaskEntity.builder().teamId("team-1").uuid("task-1").build();
        when(repository.finalizePaidTeamForDemo("u1", "o1")).thenReturn(task);

        assertEquals("team-1", service.finalizePaidTeamForDemo("u1", "o1"));

        verify(taskService).execNotifyJob(task);
    }

    @Test
    public void repeatedFinalizeOnCompletedTeamIsIdempotent() throws Exception {
        when(repository.queryMarketPayOrderEntityByOutTradeNo("u1", "o1"))
                .thenReturn(memberOrder(TradeOrderStatusEnumVO.COMPLETE));
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(GroupBuyOrderEnumVO.COMPLETE));

        assertEquals("team-1", service.finalizePaidTeamForDemo("u1", "o1"));

        verify(repository, never()).finalizePaidTeamForDemo(any(), any());
        verify(taskService, never()).execNotifyJob(any(NotifyTaskEntity.class));
    }

    @Test
    public void failedTeamCannotBeReportedAsDemoSuccess() throws Exception {
        when(repository.queryMarketPayOrderEntityByOutTradeNo("u1", "o1"))
                .thenReturn(memberOrder(TradeOrderStatusEnumVO.COMPLETE));
        when(repository.queryGroupBuyTeamByTeamId("team-1")).thenReturn(team(GroupBuyOrderEnumVO.COMPLETE_FAIL));

        try {
            service.finalizePaidTeamForDemo("u1", "o1");
            fail("expected failed team rejection");
        } catch (AppException e) {
            assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getCode());
        }

        verify(repository, never()).finalizePaidTeamForDemo(any(), any());
        verify(taskService, never()).execNotifyJob(any(NotifyTaskEntity.class));
    }

    private MarketPayOrderEntity memberOrder(TradeOrderStatusEnumVO status) {
        return MarketPayOrderEntity.builder()
                .teamId("team-1")
                .tradeOrderStatusEnumVO(status)
                .build();
    }

    private GroupBuyTeamEntity team(GroupBuyOrderEnumVO status) {
        return GroupBuyTeamEntity.builder().teamId("team-1").status(status).build();
    }
}
