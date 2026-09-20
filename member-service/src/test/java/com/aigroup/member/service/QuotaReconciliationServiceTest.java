package com.aigroup.member.service;

import com.aigroup.member.mapper.QuotaReconciliationMapper;
import com.aigroup.member.mapper.QuotaReconciliationMapper.QuotaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaReconciliationServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 8);
    @Mock
    private QuotaReconciliationMapper mapper;

    @Test
    void healthyBalanceAfterMonthlyResetDeltaHasNoMismatch() {
        // Initial grant 5, spend 3, reset +3; another account granted 8 resets with delta -3.
        when(mapper.maxUserId()).thenReturn(20L);
        when(mapper.readPage(Long.MIN_VALUE, 20L, 2)).thenReturn(List.of(
                snapshot(10L, 5, 0, 0, 5, 0), snapshot(20L, 5, 7, 2, 12, 2)));

        assertEquals(new QuotaReconciliationService.Result(2, 0, 0), service().check(DATE, 2));
        verify(mapper, never()).upsertMismatch(any(), anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void ledgerMismatchPersistsObservationWithoutChangingAccount() {
        when(mapper.maxUserId()).thenReturn(10L);
        when(mapper.readPage(Long.MIN_VALUE, 10L, 2)).thenReturn(List.of(snapshot(10L, 5, 20, 3, 24, 3)));

        assertEquals(new QuotaReconciliationService.Result(1, 1, 0), service().check(DATE, 2));
        verify(mapper).upsertMismatch(DATE, 10L, "LEDGER_BALANCE", 25L, 24L);
    }

    @Test
    void pendingFreezeMismatchIgnoresReservationLedgerEntries() {
        when(mapper.maxUserId()).thenReturn(10L);
        when(mapper.readPage(Long.MIN_VALUE, 10L, 2)).thenReturn(List.of(snapshot(10L, 5, 20, 3, 25, 4)));

        assertEquals(new QuotaReconciliationService.Result(1, 0, 1), service().check(DATE, 2));
        verify(mapper).upsertMismatch(DATE, 10L, "PENDING_FREEZE", 3L, 4L);
    }

    @Test
    void repeatRunUsesSameDailyKeyAndUpdatesOnlyMismatchEvidence() {
        when(mapper.maxUserId()).thenReturn(10L);
        when(mapper.readPage(Long.MIN_VALUE, 10L, 2)).thenReturn(
                List.of(snapshot(10L, 5, 0, 0, 4, 0)),
                List.of(snapshot(10L, 5, 0, 0, 3, 0)));

        service().check(DATE, 2);
        service().check(DATE, 2);

        verify(mapper).upsertMismatch(DATE, 10L, "LEDGER_BALANCE", 5L, 4L);
        verify(mapper).upsertMismatch(DATE, 10L, "LEDGER_BALANCE", 5L, 3L);
    }

    @Test
    void scansBoundedKeysetPagesWithoutOffset() {
        when(mapper.maxUserId()).thenReturn(30L);
        when(mapper.readPage(Long.MIN_VALUE, 30L, 2)).thenReturn(List.of(
                snapshot(10L, 5, 0, 0, 5, 0), snapshot(20L, 5, 0, 0, 5, 0)));
        when(mapper.readPage(20L, 30L, 2)).thenReturn(List.of(snapshot(30L, 5, 0, 0, 5, 0)));

        assertEquals(new QuotaReconciliationService.Result(3, 0, 0), service().check(DATE, 2));
    }

    @Test
    void rejectsUnboundedPageSize() {
        assertThrows(IllegalArgumentException.class, () -> service().check(DATE, 501));
        verifyNoInteractions(mapper);
    }

    private QuotaReconciliationService service() {
        return new QuotaReconciliationService(mapper);
    }

    private QuotaSnapshot snapshot(long userId, long free, long paid, long frozen, long ledger, long pending) {
        QuotaSnapshot snapshot = new QuotaSnapshot();
        snapshot.setUserId(userId);
        snapshot.setFreeQuotaBalance(free);
        snapshot.setPaidQuotaBalance(paid);
        snapshot.setFrozenBalance(frozen);
        snapshot.setLedgerTotal(ledger);
        snapshot.setPendingTotal(pending);
        return snapshot;
    }
}
