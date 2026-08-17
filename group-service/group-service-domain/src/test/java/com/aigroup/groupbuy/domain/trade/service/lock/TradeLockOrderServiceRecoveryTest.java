package com.aigroup.groupbuy.domain.trade.service.lock;

import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import com.aigroup.groupbuy.domain.trade.model.entity.MarketPayOrderEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayActivityEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.PayDiscountEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleCommandEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import com.aigroup.groupbuy.domain.trade.model.entity.UserEntity;
import com.aigroup.groupbuy.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeLockOrderServiceRecoveryTest {

    private ITradeRepository repository;
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> filter;
    private TradeLockOrderService service;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        repository = mock(ITradeRepository.class);
        filter = mock(BusinessLinkedList.class);
        service = new TradeLockOrderService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "tradeRuleFilter", filter);
        when(filter.apply(any(), any())).thenReturn(TradeLockRuleFilterBackEntity.builder()
                .userTakeOrderCount(0)
                .recoveryTeamStockKey("rec")
                .build());
    }

    @Test
    public void openAndJoinBothPersistSynchronously() throws Exception {
        when(repository.lockMarketPayOrder(any())).thenReturn(MarketPayOrderEntity.builder().teamId("team-1").build());

        service.lockMarketPayOrder(
                UserEntity.builder().userId("u1").build(),
                PayActivityEntity.builder().activityId(1L).teamId("team-1").validTime(30).build(),
                PayDiscountEntity.builder().outTradeNo("out-1").goodsId("g").build());

        verify(repository).lockMarketPayOrder(any(GroupBuyOrderAggregate.class));
        verify(repository, never()).recoveryTeamStock(any(), any());
    }

    @Test
    public void persistFailureIncrementsRecovery() throws Exception {
        when(repository.lockMarketPayOrder(any())).thenThrow(new RuntimeException("full"));

        assertThrows(RuntimeException.class, () -> service.lockMarketPayOrder(
                UserEntity.builder().userId("u1").build(),
                PayActivityEntity.builder().activityId(1L).teamId("team-1").validTime(30).build(),
                PayDiscountEntity.builder().outTradeNo("out-1").goodsId("g").build()));

        verify(repository).recoveryTeamStock(eq("rec"), eq(30));
    }
}
