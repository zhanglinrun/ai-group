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
import com.aigroup.member.mapper.QuotaDebitMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private QuotaDebitMapper quotaDebitMapper;
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
    void completedOrderGrantsSnapshottedBaseAsPermanentMicrocredits() {
        QuotaAccount account = account(5_000_000L, 1_000_000L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        TradeCompletedEvent event = completedEvent(500L);

        memberService.handleBenefitEvent(event);

        assertEquals(501_000_000L, account.getPaidQuotaBalance());
        ArgumentCaptor<BenefitGrantEvent> captor = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(captor.capture());
        assertEquals(500_000_000L, captor.getValue().getGrantedQuota());
        assertEquals("GRANTED", captor.getValue().getStatus());
    }

    @Test
    void duplicateCompletedOrderDoesNotGrantTwice() {
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 1_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(new BenefitGrantEvent());

        memberService.handleBenefitEvent(completedEvent(500L));

        verify(quotaAccountMapper).selectForUpdateByUserId(1001L);
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedger.class));
    }

    @Test
    void benefitEventsLockTheQuotaAccountBeforeReadingEventState() {
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 1_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(new BenefitGrantEvent());

        memberService.handleBenefitEvent(completedEvent(500L));

        InOrder order = inOrder(quotaAccountMapper, benefitGrantEventMapper);
        order.verify(quotaAccountMapper).selectForUpdateByUserId(1001L);
        order.verify(benefitGrantEventMapper).selectOne(any());
    }

    @Test
    void revokeAfterGrantWithSufficientAggregateBalanceStillNeedsManualReview() {
        QuotaAccount account = account(5_000_000L, 100_000_000L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, null, null, grantedEvent());

        memberService.handleBenefitEvent(completedEvent(500L));
        memberService.handleBenefitEvent(revokedEvent());

        assertEquals(600_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, times(2)).selectForUpdateByUserId(1001L);
        verify(quotaAccountMapper).updateById(account);
        verify(quotaFreezeMapper, never()).sumPendingPaidAmount(anyLong());
        ArgumentCaptor<QuotaLedger> ledgers = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper, times(2)).insert(ledgers.capture());
        assertEquals(List.of("GRANT", "REVOKE"), ledgers.getAllValues().stream().map(QuotaLedger::getType).toList());
        assertEquals(List.of(500_000_000L, 0L),
                ledgers.getAllValues().stream().map(QuotaLedger::getAmount).toList());
        ArgumentCaptor<BenefitGrantEvent> records = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper, times(2)).insert(records.capture());
        assertEquals(List.of("GRANTED", "REJECTED_GRANTED"),
                records.getAllValues().stream().map(BenefitGrantEvent::getStatus).toList());
        when(benefitGrantEventMapper.selectList(any())).thenReturn(records.getAllValues());
        assertEquals(Map.of("status", "GRANTED", "manualReview", true,
                "userId", "1001", "productCode", "QUOTA_500", "grantedQuotaMicro", "500000000"),
                memberService.benefitGrantDetailsForOrder("order-1"));
    }

    @Test
    void duplicateRevokeAfterGrantRecordsManualReviewOnlyOnce() {
        QuotaAccount account = account(5_000_000L, 600_000_000L, 0L);
        BenefitGrantEvent rejected = new BenefitGrantEvent();
        rejected.setStatus("REJECTED_GRANTED");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, grantedEvent(), rejected);

        memberService.handleBenefitEvent(revokedEvent());
        memberService.handleBenefitEvent(revokedEvent());

        assertEquals(600_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, times(2)).selectForUpdateByUserId(1001L);
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        verify(benefitGrantEventMapper).insert(any(BenefitGrantEvent.class));
        verify(quotaLedgerMapper).insert(any(QuotaLedger.class));
        verify(quotaFreezeMapper, never()).sumPendingPaidAmount(anyLong());
    }

    @Test
    void revokeBeforeGrantLeavesTombstoneAndSkipsLaterCompletion() {
        QuotaAccount account = account(5_000_000L, 10_000_000L, 0L);
        BenefitGrantEvent tombstone = new BenefitGrantEvent();
        tombstone.setStatus("REVOKED");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, null, null, tombstone);

        memberService.handleBenefitEvent(revokedEvent());
        memberService.handleBenefitEvent(completedEvent(500L));

        assertEquals(10_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        verify(quotaFreezeMapper, never()).sumPendingPaidAmount(anyLong());
        ArgumentCaptor<BenefitGrantEvent> records = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper, times(2)).insert(records.capture());
        assertEquals(List.of("REVOKED", "SKIPPED_REVOKED"),
                records.getAllValues().stream().map(BenefitGrantEvent::getStatus).toList());
        ArgumentCaptor<QuotaLedger> ledgers = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper, times(2)).insert(ledgers.capture());
        assertEquals(List.of(0L, 0L), ledgers.getAllValues().stream().map(QuotaLedger::getAmount).toList());
    }

    @Test
    void automaticRevokeAfterGrantDoesNotRemoveConsumedPaidQuota() {
        QuotaAccount account = account(5_000_000L, 1_000_000L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, grantedEvent());

        memberService.handleBenefitEvent(revokedEvent());

        assertEquals(1_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        ArgumentCaptor<BenefitGrantEvent> record = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(record.capture());
        assertEquals("REJECTED_GRANTED", record.getValue().getStatus());
        ArgumentCaptor<QuotaLedger> ledger = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper).insert(ledger.capture());
        assertEquals("REVOKE", ledger.getValue().getType());
        assertEquals(0L, ledger.getValue().getAmount());
    }

    @Test
    void automaticRevokeDoesNotConsumePendingPaidReservations() {
        QuotaAccount account = account(5_000_000L, 600_000_000L, 150_000_000L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, grantedEvent());

        memberService.handleBenefitEvent(revokedEvent());

        assertEquals(600_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        verify(quotaFreezeMapper, never()).sumPendingPaidAmount(anyLong());
        ArgumentCaptor<BenefitGrantEvent> record = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(record.capture());
        assertEquals("REJECTED_GRANTED", record.getValue().getStatus());
        ArgumentCaptor<QuotaLedger> ledger = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper).insert(ledger.capture());
        assertEquals(0L, ledger.getValue().getAmount());
    }

    @Test
    void laterGrantCannotProveEarlierOrderIsUnspent() {
        QuotaAccount account = account(0L, 0L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(
                null, null, null, null, null, grantedEvent());

        memberService.handleBenefitEvent(completedEvent(500L));
        memberService.debit(1001L, 500_000_000L, "llm", "spent-order-a", "legacy", null);
        TradeCompletedEvent laterOrder = completedEvent(500L);
        laterOrder.setOrderId("order-2");
        memberService.handleBenefitEvent(laterOrder);
        memberService.handleBenefitEvent(revokedEvent());

        assertEquals(500_000_000L, account.getPaidQuotaBalance());
        verify(quotaAccountMapper, times(3)).updateById(account);
        verify(quotaFreezeMapper).sumPendingPaidAmount(1001L);
        ArgumentCaptor<BenefitGrantEvent> records = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper, times(3)).insert(records.capture());
        assertEquals(List.of("GRANTED", "GRANTED", "REJECTED_GRANTED"),
                records.getAllValues().stream().map(BenefitGrantEvent::getStatus).toList());
        ArgumentCaptor<QuotaLedger> ledgers = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper, times(4)).insert(ledgers.capture());
        assertEquals(List.of("GRANT", "DEBIT", "GRANT", "REVOKE"),
                ledgers.getAllValues().stream().map(QuotaLedger::getType).toList());
        assertEquals(List.of(500_000_000L, -500_000_000L, 500_000_000L, 0L),
                ledgers.getAllValues().stream().map(QuotaLedger::getAmount).toList());
        assertEquals(account.getPaidQuotaBalance(),
                ledgers.getAllValues().stream().mapToLong(QuotaLedger::getAmount).sum());
    }

    @ParameterizedTest
    @MethodSource("invalidGrants")
    void mismatchedOrMalformedGrantRequiresManualReview(Long userId, String productCode, Long amount) {
        BenefitGrantEvent granted = grantedEvent();
        granted.setUserId(userId);
        granted.setProductCode(productCode);
        granted.setGrantedQuota(amount);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L))
                .thenReturn(account(5_000_000L, 600_000_000L, 0L));
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, granted);

        memberService.handleBenefitEvent(revokedEvent());

        verify(quotaFreezeMapper, never()).sumPendingPaidAmount(anyLong());
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        ArgumentCaptor<BenefitGrantEvent> record = ArgumentCaptor.forClass(BenefitGrantEvent.class);
        verify(benefitGrantEventMapper).insert(record.capture());
        assertEquals("REJECTED_GRANTED", record.getValue().getStatus());
        ArgumentCaptor<QuotaLedger> ledger = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper).insert(ledger.capture());
        assertEquals(0L, ledger.getValue().getAmount());
    }

    private static Stream<Arguments> invalidGrants() {
        return Stream.of(
                Arguments.of(2002L, "QUOTA_500", 500_000_000L),
                Arguments.of(1001L, "OTHER", 500_000_000L),
                Arguments.of(1001L, "QUOTA_500", (Long) null),
                Arguments.of(1001L, "QUOTA_500", 0L),
                Arguments.of(1001L, "QUOTA_500", -1L));
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
        when(benefitGrantEventMapper.selectList(any())).thenReturn(List.of(event));

        assertEquals("GRANTED", memberService.benefitGrantStatusForOrder("order-1"));
    }

    @Test
    void benefitGrantStatusForOrderReturnsRevokedForRevokedAndSkipped() {
        BenefitGrantEvent revoked = new BenefitGrantEvent();
        revoked.setStatus("REVOKED");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(List.of(revoked));
        assertEquals("REVOKED", memberService.benefitGrantStatusForOrder("order-revoked"));

        BenefitGrantEvent skipped = new BenefitGrantEvent();
        skipped.setStatus("SKIPPED_REVOKED");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(List.of(skipped));
        assertEquals("REVOKED", memberService.benefitGrantStatusForOrder("order-skipped"));
    }

    @Test
    void benefitGrantStatusForOrderKeepsGrantedWhenAutoRevokeWasRejected() {
        BenefitGrantEvent granted = new BenefitGrantEvent();
        granted.setStatus("GRANTED");
        BenefitGrantEvent rejected = new BenefitGrantEvent();
        rejected.setStatus("REJECTED_GRANTED");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(List.of(granted, rejected));

        assertEquals("GRANTED", memberService.benefitGrantStatusForOrder("order-rejected-granted"));
    }

    @Test
    void benefitGrantDetailsReturnsOnlyActualCompletedGrantProvenance() {
        BenefitGrantEvent granted = grantedEvent();
        granted.setIdempotencyKey("private-event-key");
        granted.setOrderId("order-1");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(List.of(granted));

        assertEquals(Map.of("status", "GRANTED", "userId", "1001",
                "productCode", "QUOTA_500", "grantedQuotaMicro", "500000000"),
                memberService.benefitGrantDetailsForOrder("order-1"));
        verify(benefitGrantEventMapper).selectList(any());
    }

    @Test
    void benefitGrantDetailsDoesNotInventGrantForRevokedOrPendingOrders() {
        BenefitGrantEvent revoked = new BenefitGrantEvent();
        revoked.setStatus("REVOKED");
        BenefitGrantEvent skipped = new BenefitGrantEvent();
        skipped.setStatus("SKIPPED_REVOKED");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(
                List.of(grantedEvent(), revoked), List.of(skipped), List.of());

        assertEquals(Map.of("status", "REVOKED"), memberService.benefitGrantDetailsForOrder("revoked"));
        assertEquals(Map.of("status", "REVOKED"), memberService.benefitGrantDetailsForOrder("skipped"));
        assertEquals(Map.of("status", "PENDING"), memberService.benefitGrantDetailsForOrder("pending"));
    }

    @Test
    void benefitGrantDetailsFlagsRejectedRevocationWithoutClaimingItWasRevoked() {
        BenefitGrantEvent rejected = new BenefitGrantEvent();
        rejected.setStatus("REJECTED_GRANTED");
        rejected.setEventType(CommonConstant.EVENT_GROUP_BUY_REVOKED);
        rejected.setUserId(2002L);
        rejected.setProductCode("OTHER");
        when(benefitGrantEventMapper.selectList(any())).thenReturn(
                List.of(rejected, grantedEvent()), List.of(rejected));

        assertEquals(Map.of("status", "GRANTED", "manualReview", true,
                "userId", "1001", "productCode", "QUOTA_500",
                "grantedQuotaMicro", "500000000"),
                memberService.benefitGrantDetailsForOrder("conflict"));
        assertEquals(Map.of("status", "GRANTED", "manualReview", true),
                memberService.benefitGrantDetailsForOrder("rejected-only"));
    }

    @Test
    void benefitGrantDetailsNeverUsesNonCompletionOrIncompleteRowsAsProof() {
        BenefitGrantEvent wrongType = grantedEvent();
        wrongType.setEventType(CommonConstant.EVENT_GROUP_BUY_REVOKED);
        BenefitGrantEvent incomplete = grantedEvent();
        incomplete.setGrantedQuota(null);
        when(benefitGrantEventMapper.selectList(any())).thenReturn(
                List.of(wrongType), List.of(incomplete));

        assertEquals(Map.of("status", "GRANTED"), memberService.benefitGrantDetailsForOrder("wrong-type"));
        assertEquals(Map.of("status", "GRANTED"), memberService.benefitGrantDetailsForOrder("incomplete"));
    }

    private BenefitGrantEvent grantedEvent() {
        BenefitGrantEvent granted = new BenefitGrantEvent();
        granted.setStatus("GRANTED");
        granted.setEventType(CommonConstant.EVENT_GROUP_BUY_COMPLETED);
        granted.setUserId(1001L);
        granted.setProductCode("QUOTA_500");
        granted.setGrantedQuota(500L * MemberServiceImpl.MICRO_PER_CREDIT);
        return granted;
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

    private TradeCompletedEvent completedEvent(long base) {
        TradeCompletedEvent event = new TradeCompletedEvent();
        event.setEventType(CommonConstant.EVENT_GROUP_BUY_COMPLETED);
        event.setUserId(1001L);
        event.setOrderId("order-1");
        event.setProductCode("QUOTA_500");
        event.setBaseQuota(base);
        return event;
    }

    private TradeCompletedEvent revokedEvent() {
        TradeCompletedEvent event = completedEvent(500L);
        event.setEventType(CommonConstant.EVENT_GROUP_BUY_REVOKED);
        return event;
    }
}
