package com.aigroup.member.service.impl;

import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaFreeze;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import com.aigroup.member.vo.QuotaFreezeStatusVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplQuotaSettlementTest {

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
    void freezePersistsStablePayloadIdentityAndCanonicalManagedOwner() {
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(10_000L, 0L, 0L));
        when(quotaFreezeMapper.sumPendingFreeAmount(1001L)).thenReturn(0L);
        when(quotaFreezeMapper.sumPendingPaidAmount(1001L)).thenReturn(0L);
        when(quotaAccountMapper.freezeBalanceIfAvailable(1001L, 8_000L)).thenReturn(1);

        Map<String, Object> result = memberService.freeze(
                1001L, 8_000L, 2_000L, " IMAGE ", "billing-1", " AI-Agent ");

        ArgumentCaptor<QuotaFreeze> captor = ArgumentCaptor.forClass(QuotaFreeze.class);
        verify(quotaFreezeMapper).insert(captor.capture());
        QuotaFreeze persisted = captor.getValue();
        assertAll(
                () -> assertNotNull(result.get("freezeId")),
                () -> assertEquals(8_000L, result.get("amount")),
                () -> assertEquals(8_000L, persisted.getRequestedAmount()),
                () -> assertEquals(2_000L, persisted.getMinAmount()),
                () -> assertEquals("image", persisted.getAbilityCode()),
                () -> assertEquals("ai-agent", persisted.getOwnerService()),
                () -> assertTrue(persisted.getRequestFingerprint().matches("[0-9a-f]{64}"))
        );
    }

    @Test
    void freezeRejectsUnknownSettlementOwnersBeforeTouchingTheAccount() {
        assertThrows(BusinessException.class, () -> memberService.freeze(
                1001L, 8_000L, 2_000L, "image", "billing-1", "unknown-service"));

        verify(quotaAccountMapper, never()).selectForUpdateByUserId(any());
        verify(quotaFreezeMapper, never()).insert(any());
    }

    @Test
    void sameRequestIdAndSamePayloadReusesThePendingReservation() {
        QuotaFreeze existing = pendingFreeze(3_000L, 3_000L, 0L);
        existing.setFreezeId("existing-freeze");
        existing.setRequestedAmount(4_000L);
        existing.setMinAmount(2_000L);
        existing.setAbilityCode("deep-search");
        existing.setOwnerService("ai-agent");
        existing.setRequestFingerprint(null);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(10_000L, 0L, 3_000L));
        when(quotaFreezeMapper.selectForUpdateByUserIdAndRequestId(1001L, "billing-1"))
                .thenReturn(existing);

        Map<String, Object> result = memberService.freeze(
                1001L, 4_000L, 2_000L, "deep-search", "billing-1", "AI-AGENT");

        assertEquals("existing-freeze", result.get("freezeId"));
        verify(quotaAccountMapper, never()).freezeBalanceIfAvailable(any(), anyLong());
        verify(quotaFreezeMapper, never()).insert(any());
    }

    @Test
    void sameRequestIdRejectsAnyPayloadDrift() {
        QuotaFreeze existing = pendingFreeze(3_000L, 3_000L, 0L);
        existing.setRequestedAmount(4_000L);
        existing.setMinAmount(2_000L);
        existing.setAbilityCode("deep-search");
        existing.setOwnerService("ai-agent");
        existing.setRequestFingerprint(null);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(10_000L, 0L, 3_000L));
        when(quotaFreezeMapper.selectForUpdateByUserIdAndRequestId(1001L, "billing-1"))
                .thenReturn(existing);

        assertAll(
                () -> assertThrows(BusinessException.class, () -> memberService.freeze(
                        1001L, 4_001L, 2_000L, "deep-search", "billing-1", "ai-agent")),
                () -> assertThrows(BusinessException.class, () -> memberService.freeze(
                        1001L, 4_000L, 2_001L, "deep-search", "billing-1", "ai-agent")),
                () -> assertThrows(BusinessException.class, () -> memberService.freeze(
                        1001L, 4_000L, 2_000L, "image", "billing-1", "ai-agent")),
                () -> assertThrows(BusinessException.class, () -> memberService.freeze(
                        1001L, 4_000L, 2_000L, "deep-search", "billing-1", "legacy"))
        );
        verify(quotaAccountMapper, never()).freezeBalanceIfAvailable(any(), anyLong());
        verify(quotaFreezeMapper, never()).insert(any());
    }

    @Test
    void confirmIsReplaySafeAndReturnsTheRealTerminalState() {
        QuotaFreeze freeze = pendingFreeze(7_000L, 5_000L, 2_000L);
        QuotaAccount account = account(5_000L, 10_000L, 7_000L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.selectForUpdateByFreezeId("freeze-1")).thenReturn(freeze);

        QuotaFreezeStatusVO first = memberService.confirmWithStatus("freeze-1", 6_000L);
        QuotaFreezeStatusVO replay = memberService.confirmWithStatus("freeze-1", 6_000L);

        assertAll(
                () -> assertEquals("CONFIRMED", first.getStatus()),
                () -> assertEquals(6_000L, first.getSettledAmount()),
                () -> assertEquals("CONFIRMED", replay.getStatus()),
                () -> assertEquals(0L, account.getFrozenBalance()),
                () -> assertEquals(0L, account.getFreeQuotaBalance()),
                () -> assertEquals(9_000L, account.getPaidQuotaBalance())
        );
        verify(quotaAccountMapper, times(1)).updateById(account);
        verify(quotaFreezeMapper, times(1)).updateById(freeze);
        verify(quotaLedgerMapper, times(1)).insert(any());
    }

    @Test
    void confirmReplayWithDifferentAmountIsAConflict() {
        QuotaFreeze freeze = pendingFreeze(1_000L, 1_000L, 0L);
        freeze.setStatus("CONFIRMED");
        freeze.setSettledAmount(800L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);

        assertThrows(BusinessException.class,
                () -> memberService.confirmWithStatus("freeze-1", 900L));
        verify(quotaAccountMapper, never()).selectForUpdateByUserId(any());
    }

    @Test
    void releaseIsReplaySafeAndReturnsTheRealTerminalState() {
        QuotaFreeze freeze = pendingFreeze(5_000L, 5_000L, 0L);
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(5_000L, 0L, 5_000L));
        when(quotaFreezeMapper.selectForUpdateByFreezeId("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.releaseFrozenBalance(1001L, 5_000L)).thenReturn(1);

        QuotaFreezeStatusVO first = memberService.releaseWithStatus("freeze-1");
        QuotaFreezeStatusVO replay = memberService.releaseWithStatus("freeze-1");

        assertEquals("RELEASED", first.getStatus());
        assertEquals("RELEASED", replay.getStatus());
        verify(quotaAccountMapper, times(1)).releaseFrozenBalance(1001L, 5_000L);
        verify(quotaFreezeMapper, times(1)).updateById(freeze);
        verify(quotaLedgerMapper, times(1)).insert(any());
    }

    @Test
    void oppositeTerminalStatesAreReturnedInsteadOfSilentlyPretendingSuccess() {
        QuotaFreeze released = pendingFreeze(1_000L, 1_000L, 0L);
        released.setStatus("RELEASED");
        QuotaFreeze confirmed = pendingFreeze(1_000L, 1_000L, 0L);
        confirmed.setStatus("CONFIRMED");
        confirmed.setSettledAmount(600L);
        when(quotaFreezeMapper.selectById("released")).thenReturn(released);
        when(quotaFreezeMapper.selectById("confirmed")).thenReturn(confirmed);

        assertEquals("RELEASED", memberService.confirmWithStatus("released", 600L).getStatus());
        assertEquals("CONFIRMED", memberService.releaseWithStatus("confirmed").getStatus());
        verify(quotaAccountMapper, never()).selectForUpdateByUserId(any());
    }

    @Test
    void confirmReleaseRaceObservesTheWinnerAfterTakingLocks() {
        QuotaFreeze pendingForConfirm = pendingFreeze(1_000L, 1_000L, 0L);
        QuotaFreeze releasedWinner = pendingFreeze(1_000L, 1_000L, 0L);
        releasedWinner.setStatus("RELEASED");
        when(quotaFreezeMapper.selectById("confirm-loser")).thenReturn(pendingForConfirm);
        when(quotaFreezeMapper.selectForUpdateByFreezeId("confirm-loser")).thenReturn(releasedWinner);

        QuotaFreeze pendingForRelease = pendingFreeze(1_000L, 1_000L, 0L);
        QuotaFreeze confirmedWinner = pendingFreeze(1_000L, 1_000L, 0L);
        confirmedWinner.setStatus("CONFIRMED");
        confirmedWinner.setSettledAmount(700L);
        when(quotaFreezeMapper.selectById("release-loser")).thenReturn(pendingForRelease);
        when(quotaFreezeMapper.selectForUpdateByFreezeId("release-loser")).thenReturn(confirmedWinner);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account(5_000L, 0L, 1_000L));

        assertEquals("RELEASED",
                memberService.confirmWithStatus("confirm-loser", 700L).getStatus());
        assertEquals("CONFIRMED",
                memberService.releaseWithStatus("release-loser").getStatus());
        verify(quotaAccountMapper, never()).releaseFrozenBalance(any(), anyLong());
        verify(quotaAccountMapper, never()).updateById(any());
        verify(quotaLedgerMapper, never()).insert(any());
    }

    @Test
    void terminalStateCanBeRecoveredByFreezeIdOrBillingRequest() {
        QuotaFreeze freeze = pendingFreeze(3_000L, 2_000L, 1_000L);
        freeze.setStatus("CONFIRMED");
        freeze.setSettledAmount(2_500L);
        freeze.setRequestId("billing-1");
        freeze.setOwnerService("ai-agent");
        when(quotaFreezeMapper.selectById("freeze-1")).thenReturn(freeze);
        when(quotaFreezeMapper.selectByUserIdAndRequestId(1001L, "billing-1")).thenReturn(freeze);

        QuotaFreezeStatusVO byId = memberService.queryFreeze("freeze-1");
        QuotaFreezeStatusVO byRequest = memberService.queryFreezeByRequest(1001L, "billing-1");

        assertAll(
                () -> assertEquals("CONFIRMED", byId.getStatus()),
                () -> assertEquals(2_500L, byId.getSettledAmount()),
                () -> assertEquals(3_000L, byId.getRequestedAmount()),
                () -> assertEquals(1_000L, byId.getMinAmount()),
                () -> assertEquals("billing-1", byRequest.getRequestId()),
                () -> assertEquals("fingerprint-1", byRequest.getRequestFingerprint()),
                () -> assertEquals("ai-agent", byRequest.getOwnerService())
        );
    }

    @Test
    void expiredScansDelegateManagedAndLegacyRowsSeparately() {
        when(quotaFreezeMapper.selectExpiredPendingFreezeIds(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(List.of("legacy"));
        when(quotaFreezeMapper.selectExpiredManagedPendingFreezeIds(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(List.of("managed"));

        assertEquals(List.of("legacy"), memberService.listExpiredPendingFreezeIds(0, 0));
        assertEquals(List.of("managed"), memberService.listExpiredManagedPendingFreezeIds(0, 0));
        verify(quotaFreezeMapper).selectExpiredPendingFreezeIds(any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(1));
        verify(quotaFreezeMapper).selectExpiredManagedPendingFreezeIds(any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(1));
    }

    private QuotaAccount account(long free, long paid, long frozen) {
        QuotaAccount account = new QuotaAccount();
        account.setUserId(1001L);
        account.setFreeQuotaBalance(free);
        account.setPaidQuotaBalance(paid);
        account.setFrozenBalance(frozen);
        return account;
    }

    private QuotaFreeze pendingFreeze(long amount, long free, long paid) {
        QuotaFreeze freeze = new QuotaFreeze();
        freeze.setFreezeId("freeze-1");
        freeze.setUserId(1001L);
        freeze.setAmount(amount);
        freeze.setFreeAmount(free);
        freeze.setPaidAmount(paid);
        freeze.setSettledAmount(0L);
        freeze.setRequestedAmount(amount);
        freeze.setMinAmount(Math.min(amount, 1_000L));
        freeze.setAbilityCode("llm");
        freeze.setStatus("PENDING");
        freeze.setRequestFingerprint("fingerprint-1");
        return freeze;
    }
}
