package com.aigroup.member.service.impl;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaFreeze;
import com.aigroup.member.entity.QuotaLedger;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplBenefitTest {

    @Mock
    private BenefitGrantEventMapper benefitGrantEventMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private QuotaAccountMapper quotaAccountMapper;
    @Mock
    private QuotaFreezeMapper quotaFreezeMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private MemberServiceImpl memberService;

    @Test
    void freezeUsesFreeFirstAndAtomicallyShortensToAvailableBalance() {
        QuotaAccount account = account(5_000_000L, 2_000_000L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.sumPendingFreeAmount(1001L)).thenReturn(0L);
        when(quotaFreezeMapper.sumPendingPaidAmount(1001L)).thenReturn(0L);
        when(quotaAccountMapper.freezeBalanceIfAvailable(1001L, 7_000_000L)).thenReturn(1);

        Map<String, Object> result = memberService.freeze(
                1001L, 10_000_000L, 1_000_000L, "llm", "request-1");

        assertEquals(7_000_000L, result.get("amount"));
        ArgumentCaptor<QuotaFreeze> captor = ArgumentCaptor.forClass(QuotaFreeze.class);
        verify(quotaFreezeMapper).insert(captor.capture());
        assertEquals(5_000_000L, captor.getValue().getFreeAmount());
        assertEquals(2_000_000L, captor.getValue().getPaidAmount());
    }

    @Test
    void freezeRejectsWhenAvailableIsBelowMinimum() {
        QuotaAccount account = account(100L, 0L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.sumPendingFreeAmount(1001L)).thenReturn(0L);
        when(quotaFreezeMapper.sumPendingPaidAmount(1001L)).thenReturn(0L);

        assertThrows(BusinessException.class,
                () -> memberService.freeze(1001L, 1_000L, 256L, "llm", "request-1"));
        verify(quotaAccountMapper, never()).freezeBalanceIfAvailable(any(), anyLong());
    }

    @Test
    void freezeReusesConcurrentIdempotentReservation() {
        QuotaAccount account = account(5_000_000L, 0L, 0L);
        QuotaFreeze existing = freeze(3_000_000L, 3_000_000L, 0L);
        existing.setFreezeId("freeze-existing");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.selectForUpdateByUserIdAndRequestId(1001L, "duplicate")).thenReturn(existing);

        Map<String, Object> result = memberService.freeze(
                1001L, 3_000_000L, 3_000_000L, "llm", "duplicate");

        assertEquals("freeze-existing", result.get("freezeId"));
        assertEquals(3_000_000L, result.get("amount"));
        verify(quotaAccountMapper, never()).freezeBalanceIfAvailable(any(), anyLong());
    }

    @Test
    void confirmSettlesActualFreeFirstAndReleasesUnusedReservation() {
        QuotaFreeze freeze = freeze(7_000_000L, 5_000_000L, 2_000_000L);
        QuotaAccount account = account(5_000_000L, 10_000_000L, 7_000_000L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);
        when(quotaFreezeMapper.selectForUpdateByFreezeId("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);

        memberService.confirm("freeze-1", 6_000_000L);

        assertEquals(0L, account.getFreeQuotaBalance());
        assertEquals(9_000_000L, account.getPaidQuotaBalance());
        assertEquals(0L, account.getFrozenBalance());
        assertEquals(6_000_000L, freeze.getSettledAmount());
        assertEquals("CONFIRMED", freeze.getStatus());
    }

    @Test
    void confirmIsIdempotent() {
        QuotaFreeze freeze = freeze(1_000L, 1_000L, 0L);
        freeze.setStatus("CONFIRMED");
        freeze.setSettledAmount(1_000L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);

        memberService.confirm("freeze-1", 1_000L);

        verify(quotaAccountMapper, never()).selectForUpdateByUserId(any());
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedger.class));
    }

    @Test
    void confirmRejectsConflictingAmountForSameIdempotentFreeze() {
        QuotaFreeze freeze = freeze(1_000L, 1_000L, 0L);
        freeze.setStatus("CONFIRMED");
        freeze.setSettledAmount(800L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);

        assertThrows(BusinessException.class, () -> memberService.confirm("freeze-1", 900L));

        verify(quotaAccountMapper, never()).selectForUpdateByUserId(any());
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedger.class));
    }

    @Test
    void releaseThrowsWhenFrozenBalanceDoesNotMatch() {
        QuotaFreeze freeze = freeze(5L, 5L, 0L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);
        when(quotaFreezeMapper.selectForUpdateByFreezeId("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(5L, 0L, 5L));
        when(quotaAccountMapper.releaseFrozenBalance(1001L, 5L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> memberService.release("freeze-1"));
    }

    @Test
    void completedOrderGrantsSnapshottedBaseAndBonusAsPermanentMicrocredits() {
        QuotaAccount account = account(5_000_000L, 1_000_000L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        TradeCompletedEvent event = completedEvent(500L, 50L);

        memberService.handleBenefitEvent(event);

        assertEquals(551_000_000L, account.getPaidQuotaBalance());
        ArgumentCaptor<BenefitGrantEvent> captor = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(captor.capture());
        assertEquals(550_000_000L, captor.getValue().getGrantedQuota());
        assertEquals("GRANTED", captor.getValue().getStatus());
    }

    @Test
    void duplicateCompletedOrderDoesNotGrantTwice() {
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 1_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(new BenefitGrantEvent());

        memberService.handleBenefitEvent(completedEvent(500L, 50L));

        verify(quotaAccountMapper).selectForUpdateByUserId(1001L);
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedger.class));
    }

    @Test
    void benefitEventsLockTheQuotaAccountBeforeReadingEventState() {
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 1_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(new BenefitGrantEvent());

        memberService.handleBenefitEvent(completedEvent(500L, 50L));

        InOrder order = inOrder(quotaAccountMapper, benefitGrantEventMapper);
        order.verify(quotaAccountMapper).selectForUpdateByUserId(1001L);
        order.verify(benefitGrantEventMapper).selectOne(any());
    }

    @Test
    void automaticRevokeAfterGrantDoesNotRemoveConsumedPaidQuota() {
        BenefitGrantEvent granted = new BenefitGrantEvent();
        granted.setStatus("GRANTED");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 1_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, granted);
        TradeCompletedEvent event = completedEvent(500L, 0L);
        event.setEventType(CommonConstant.EVENT_GROUP_BUY_REVOKED);

        memberService.handleBenefitEvent(event);

        verify(quotaAccountMapper).selectForUpdateByUserId(1001L);
        ArgumentCaptor<BenefitGrantEvent> captor = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(captor.capture());
        assertEquals("REJECTED_GRANTED", captor.getValue().getStatus());
    }

    @Test
    void monthlyResetReplacesOnlyFreeBalanceAndIsIdempotent() {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        QuotaAccount account = account(2_000_000L, 700_000_000L, 250L);
        account.setLastFreeGrantMonth("2000-01");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);

        boolean first = memberService.grantMonthlyQuotaForUser(1001L, month);
        boolean second = memberService.grantMonthlyQuotaForUser(1001L, month);

        assertEquals(true, first);
        assertEquals(false, second);
        assertEquals(5_000_000L, account.getFreeQuotaBalance());
        assertEquals(700_000_000L, account.getPaidQuotaBalance());
        assertEquals(250L, account.getFrozenBalance());
    }

    @Test
    void benefitGrantStatusForOrderReturnsGrantedOnlyForGrantedCompletion() {
        BenefitGrantEvent event = new BenefitGrantEvent();
        event.setStatus("GRANTED");
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(event);

        assertEquals("GRANTED", memberService.benefitGrantStatusForOrder("order-1"));
    }

    @Test
    void ledgerQueryIsLimitedToTheAuthenticatedUser() {
        QuotaLedger row = new QuotaLedger();
        row.setType("CONFIRM");
        row.setAmount(-30L);
        when(quotaLedgerMapper.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuotaLedger> query =
                    invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("LIMIT 50"));
            assertTrue(query.getParamNameValuePairs().containsValue(1001L));
            return List.of(row);
        });

        var result = memberService.listQuotaLedger(1001L);

        assertEquals(1, result.size());
        assertEquals(-30L, result.getFirst().getAmount());
    }

    private QuotaAccount account(long free, long paid, long frozen) {
        QuotaAccount account = new QuotaAccount();
        account.setUserId(1001L);
        account.setFreeQuotaBalance(free);
        account.setPaidQuotaBalance(paid);
        account.setFrozenBalance(frozen);
        return account;
    }

    private QuotaFreeze freeze(long amount, long free, long paid) {
        QuotaFreeze freeze = new QuotaFreeze();
        freeze.setFreezeId("freeze-1");
        freeze.setUserId(1001L);
        freeze.setAmount(amount);
        freeze.setFreeAmount(free);
        freeze.setPaidAmount(paid);
        freeze.setSettledAmount(0L);
        freeze.setStatus("PENDING");
        freeze.setAbilityCode("llm");
        return freeze;
    }

    private TradeCompletedEvent completedEvent(long base, long bonus) {
        TradeCompletedEvent event = new TradeCompletedEvent();
        event.setEventType(CommonConstant.EVENT_GROUP_BUY_COMPLETED);
        event.setUserId(1001L);
        event.setOrderId("order-1");
        event.setProductCode("QUOTA_500");
        event.setBaseQuota(base);
        event.setBonusQuota(bonus);
        return event;
    }
}
