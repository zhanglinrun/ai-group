package com.aigroup.member.service.impl;

import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaDebit;
import com.aigroup.member.entity.QuotaLedger;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaDebitMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplDebitTest {

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
    void debitUsesFreeFirstAndDoesNotTouchFrozenBalance() {
        QuotaAccount account = account(5_000_000L, 2_000_000L, 1_000_000L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.sumPendingFreeAmount(1001L)).thenReturn(1_000_000L);
        when(quotaFreezeMapper.sumPendingPaidAmount(1001L)).thenReturn(0L);
        when(quotaDebitMapper.selectForUpdateByUserIdAndRequestId(1001L, "agent:run:call:1")).thenReturn(null);

        Map<String, Object> result = memberService.debit(
                1001L, 4_000_000L, "llm", "agent:run:call:1", "agent-service", "run_1");

        assertEquals(4_000_000L, result.get("amount"));
        assertEquals("DEBITED", result.get("status"));
        assertEquals(1_000_000L, account.getFreeQuotaBalance());
        assertEquals(2_000_000L, account.getPaidQuotaBalance());
        assertEquals(1_000_000L, account.getFrozenBalance());
        ArgumentCaptor<QuotaDebit> debitCaptor = ArgumentCaptor.forClass(QuotaDebit.class);
        verify(quotaDebitMapper).insert(debitCaptor.capture());
        assertEquals(4_000_000L, debitCaptor.getValue().getFreeAmount());
        assertEquals(0L, debitCaptor.getValue().getPaidAmount());
        ArgumentCaptor<QuotaLedger> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        assertEquals("DEBIT", ledgerCaptor.getValue().getType());
        assertEquals(-4_000_000L, ledgerCaptor.getValue().getAmount());
    }

    @Test
    void debitRejectsWhenAvailableIsBelowAmount() {
        QuotaAccount account = account(100L, 0L, 0L);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.sumPendingFreeAmount(1001L)).thenReturn(0L);
        when(quotaFreezeMapper.sumPendingPaidAmount(1001L)).thenReturn(0L);
        when(quotaDebitMapper.selectForUpdateByUserIdAndRequestId(1001L, "req-1")).thenReturn(null);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> memberService.debit(1001L, 256L, "llm", "req-1", "legacy", null));
        assertEquals(ErrorCodeEnum.QUOTA_INSUFFICIENT.getCode(), thrown.getCode());
        verify(quotaDebitMapper, never()).insert(any(QuotaDebit.class));
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
    }

    @Test
    void debitReusesIdempotentRequestId() {
        QuotaAccount account = account(5_000_000L, 0L, 0L);
        QuotaDebit existing = new QuotaDebit();
        existing.setDebitId("debit-existing");
        existing.setUserId(1001L);
        existing.setAmount(3_000_000L);
        existing.setRequestedAmount(3_000_000L);
        existing.setStatus("DEBITED");
        existing.setAbilityCode("llm");
        existing.setOwnerService("agent-service");
        existing.setRequestId("duplicate");
        existing.setTraceId("run_1");
        existing.setRequestFingerprint(null);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaDebitMapper.selectForUpdateByUserIdAndRequestId(1001L, "duplicate")).thenReturn(existing);

        Map<String, Object> result = memberService.debit(
                1001L, 3_000_000L, "llm", "duplicate", "agent-service", "run_1");

        assertEquals("debit-existing", result.get("debitId"));
        assertEquals(3_000_000L, result.get("amount"));
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
        verify(quotaDebitMapper, never()).insert(any(QuotaDebit.class));
    }

    @Test
    void debitRejectsRequestIdPayloadDrift() {
        QuotaAccount account = account(5_000_000L, 0L, 0L);
        QuotaDebit existing = new QuotaDebit();
        existing.setDebitId("debit-existing");
        existing.setRequestedAmount(3_000_000L);
        existing.setStatus("DEBITED");
        existing.setAbilityCode("llm");
        existing.setOwnerService("agent-service");
        existing.setTraceId("run_1");
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaDebitMapper.selectForUpdateByUserIdAndRequestId(1001L, "duplicate")).thenReturn(existing);

        assertThrows(BusinessException.class, () -> memberService.debit(
                1001L, 4_000_000L, "llm", "duplicate", "agent-service", "run_1"));
        verify(quotaAccountMapper, never()).updateById(any(QuotaAccount.class));
    }

    private QuotaAccount account(long free, long paid, long frozen) {
        QuotaAccount quotaAccount = new QuotaAccount();
        quotaAccount.setId(1L);
        quotaAccount.setUserId(1001L);
        quotaAccount.setFreeQuotaBalance(free);
        quotaAccount.setPaidQuotaBalance(paid);
        quotaAccount.setFrozenBalance(frozen);
        return quotaAccount;
    }
}
