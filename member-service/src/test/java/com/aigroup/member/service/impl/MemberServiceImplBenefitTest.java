package com.aigroup.member.service.impl;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.config.QuotaAbilityProperties;
import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.entity.MemberAccount;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaFreeze;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.MemberAccountMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceImplBenefitTest {

    @Mock
    private BenefitGrantEventMapper benefitGrantEventMapper;
    @Mock
    private MemberAccountMapper memberAccountMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private QuotaAccountMapper quotaAccountMapper;
    @Mock
    private QuotaFreezeMapper quotaFreezeMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private QuotaAbilityProperties quotaAbilityProperties;

    @InjectMocks
    private MemberServiceImpl memberService;

    @Test
    void benefitGrantStatusForOrder_returnsPendingWhenNoEvent() {
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null);

        assertEquals("PENDING", memberService.benefitGrantStatusForOrder("order-1"));
    }

    @Test
    void benefitGrantStatusForOrder_returnsGrantedWhenCompleted() {
        BenefitGrantEvent event = new BenefitGrantEvent();
        event.setStatus("GRANTED");
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(event);

        assertEquals("GRANTED", memberService.benefitGrantStatusForOrder("order-1"));
    }

    @Test
    void benefitGrantStatusForOrder_returnsRevokedWhenRevoked() {
        BenefitGrantEvent event = new BenefitGrantEvent();
        event.setStatus("REVOKED");
        when(benefitGrantEventMapper.selectOne(any())).thenReturn(event);

        assertEquals("REVOKED", memberService.benefitGrantStatusForOrder("order-1"));
    }

    @Test
    void release_throwsWhenFrozenBalanceMismatch() {
        QuotaFreeze freeze = new QuotaFreeze();
        freeze.setFreezeId("freeze-1");
        freeze.setUserId(1001L);
        freeze.setAmount(5);
        freeze.setStatus("PENDING");
        when(quotaFreezeMapper.selectForUpdateByFreezeId("freeze-1")).thenReturn(freeze);
        when(quotaAccountMapper.releaseFrozenBalance(1001L, 5)).thenReturn(0);

        assertThrows(BusinessException.class, () -> memberService.release("freeze-1"));
    }

    @Test
    void freeze_reusesConcurrentFreezeAfterAccountLock() {
        QuotaAccount account = new QuotaAccount();
        account.setUserId(1001L);
        account.setPeriodQuotaBalance(20);
        account.setTopupQuotaBalance(0);
        account.setFrozenBalance(3);
        QuotaFreeze existing = new QuotaFreeze();
        existing.setFreezeId("freeze-existing");
        existing.setUserId(1001L);
        existing.setRequestId("request-duplicate");
        existing.setAmount(3);
        existing.setStatus("PENDING");

        when(quotaFreezeMapper.selectByUserIdAndRequestId(1001L, "request-duplicate")).thenReturn(null);
        when(quotaAbilityProperties.resolveCost("plan_solve", 1)).thenReturn(3);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(account);
        when(quotaFreezeMapper.selectForUpdateByUserIdAndRequestId(1001L, "request-duplicate"))
                .thenReturn(existing);

        Map<String, String> result = memberService.freeze(1001L, "plan_solve", 1, "request-duplicate");

        assertEquals("freeze-existing", result.get("freezeId"));
        verify(quotaAccountMapper, never()).freezeBalanceIfAvailable(any(), any(Integer.class));
        verify(quotaFreezeMapper, never()).insert(any());
        verify(quotaLedgerMapper, never()).insert(any());
    }

    @Test
    void handleGroupBuyRevoked_rollsBackPeriodQuotaAndDowngradesTier() {
        BenefitGrantEvent granted = new BenefitGrantEvent();
        granted.setId(10L);
        granted.setOrderId("order-1");
        granted.setStatus("GRANTED");
        granted.setTierEffect("PRO");
        granted.setPeriodQuotaGranted(100);
        granted.setMemberDaysDelta(30);
        granted.setTopupQuotaGranted(0);

        MemberAccount member = new MemberAccount();
        member.setUserId(1001L);
        member.setTier("PRO");
        member.setExpireAt(LocalDateTime.now().plusDays(30));

        QuotaAccount quota = new QuotaAccount();
        quota.setUserId(1001L);
        quota.setPeriodQuotaBalance(100);
        quota.setTopupQuotaBalance(0);
        quota.setFrozenBalance(0);

        when(benefitGrantEventMapper.selectOne(any())).thenReturn(null, granted);
        when(memberAccountMapper.selectOne(any())).thenReturn(member);
        when(quotaAccountMapper.selectForUpdateByUserId(1001L)).thenReturn(quota);
        when(benefitGrantEventMapper.selectCount(any())).thenReturn(0L);
        when(benefitGrantEventMapper.insert(any())).thenReturn(1);
        when(quotaLedgerMapper.insert(any())).thenReturn(1);

        TradeCompletedEvent event = new TradeCompletedEvent();
        event.setEventType(CommonConstant.EVENT_GROUP_BUY_REVOKED);
        event.setUserId(1001L);
        event.setOrderId("order-1");
        event.setProductCode("PRO_MONTH");

        memberService.handleBenefitEvent(event);

        assertEquals("FREE", member.getTier());
        assertEquals(20, quota.getPeriodQuotaBalance());
        verify(benefitGrantEventMapper).updateById(granted);
        assertEquals("REVOKED", granted.getStatus());
    }
}
