package com.aigroup.member.service.impl;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.config.QuotaAbilityProperties;
import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.entity.MemberAccount;
import com.aigroup.member.entity.ProductSku;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaFreeze;
import com.aigroup.member.entity.QuotaLedger;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.MemberAccountMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.SkuVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private static final String SKU_TYPE_MEMBER = "MEMBER";
    private static final String SKU_TYPE_TOPUP = "TOPUP";
    private static final String SKU_TYPE_FREE = "FREE";
    private static final int FREE_MONTHLY_QUOTA = 20;
    private static final String FREEZE_STATUS_PENDING = "PENDING";
    private static final String FREEZE_STATUS_CONFIRMED = "CONFIRMED";
    private static final String FREEZE_STATUS_RELEASED = "RELEASED";
    private static final String LEDGER_FREEZE = "FREEZE";
    private static final String LEDGER_CONFIRM = "CONFIRM";
    private static final String LEDGER_RELEASE = "RELEASE";
    private static final String LEDGER_GRANT = "GRANT";
    private static final String LEDGER_REVOKE = "REVOKE";
    private static final String LEDGER_MONTHLY_GRANT = "MONTHLY_GRANT";
    private static final String LEDGER_ADMIN_ADJUST = "ADMIN_ADJUST";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ProductSkuMapper productSkuMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final QuotaAccountMapper quotaAccountMapper;
    private final QuotaFreezeMapper quotaFreezeMapper;
    private final BenefitGrantEventMapper benefitGrantEventMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final QuotaAbilityProperties quotaAbilityProperties;
    private final PlatformTransactionManager transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initFree(Long userId) {
        MemberAccount existing = memberAccountMapper.selectOne(
                new LambdaQueryWrapper<MemberAccount>().eq(MemberAccount::getUserId, userId));
        if (existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        MemberAccount member = new MemberAccount();
        member.setUserId(userId);
        member.setTier("FREE");
        member.setStatus(1);
        member.setLastPeriodGrantMonth(currentMonth());
        member.setCreateTime(now);
        member.setUpdateTime(now);
        memberAccountMapper.insert(member);

        QuotaAccount quota = new QuotaAccount();
        quota.setUserId(userId);
        quota.setPeriodQuotaBalance(FREE_MONTHLY_QUOTA);
        quota.setTopupQuotaBalance(0);
        quota.setFrozenBalance(0);
        quota.setUpdateTime(now);
        quotaAccountMapper.insert(quota);
        appendLedger(userId, LEDGER_GRANT, FREE_MONTHLY_QUOTA, null, "free", "init free quota");
    }

    @Override
    public List<SkuVO> listSkus() {
        return productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getStatus, 1)
                        .in(ProductSku::getSkuType, SKU_TYPE_MEMBER, SKU_TYPE_TOPUP)
        ).stream().map(sku -> {
            SkuVO vo = new SkuVO();
            BeanUtils.copyProperties(sku, vo);
            return vo;
        }).toList();
    }

    @Override
    public MemberSummaryVO summary(Long userId) {
        MemberAccount member = memberAccountMapper.selectOne(
                new LambdaQueryWrapper<MemberAccount>().eq(MemberAccount::getUserId, userId));
        QuotaAccount quota = quotaAccountMapper.selectOne(
                new LambdaQueryWrapper<QuotaAccount>().eq(QuotaAccount::getUserId, userId));
        if (member == null || quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        MemberSummaryVO vo = new MemberSummaryVO();
        vo.setUserId(userId);
        String effectiveTier = member.getTier();
        if ("PRO".equals(effectiveTier)
                && member.getExpireAt() != null && member.getExpireAt().isBefore(LocalDateTime.now())) {
            // Reconcile expired PRO on read so the API never reports a lapsed membership as PRO.
            effectiveTier = "FREE";
        }
        vo.setTier(effectiveTier);
        vo.setStartAt(member.getStartAt());
        vo.setExpireAt(member.getExpireAt());
        vo.setPeriodQuotaBalance(quota.getPeriodQuotaBalance());
        vo.setTopupQuotaBalance(quota.getTopupQuotaBalance());
        vo.setFrozenBalance(quota.getFrozenBalance());
        int available = quota.getPeriodQuotaBalance() + quota.getTopupQuotaBalance() - quota.getFrozenBalance();
        vo.setAvailableQuota(Math.max(available, 0));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> freeze(Long userId, String abilityCode, int multiplier, String requestId) {
        // 幂等：同一 requestId（agent 请求ID）重复预扣直接返回既有 freezeId，避免网络重试重复冻结导致配额泄漏。
        // 顺序重试由此 SELECT 命中；极少数并发同 requestId 由 uk_user_request 唯一键 + 事务回滚兜底（不产生泄漏）。
        if (StringUtils.hasText(requestId)) {
            QuotaFreeze existing = quotaFreezeMapper.selectByUserIdAndRequestId(userId, requestId);
            if (existing != null) {
                return Map.of("freezeId", existing.getFreezeId());
            }
        }
        int cost = quotaAbilityProperties.resolveCost(abilityCode, multiplier);
        QuotaAccount locked = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (locked == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        int available = locked.getPeriodQuotaBalance() + locked.getTopupQuotaBalance() - locked.getFrozenBalance();
        if (available < cost) {
            throw new BusinessException(ErrorCodeEnum.QUOTA_INSUFFICIENT);
        }
        int updated = quotaAccountMapper.freezeBalanceIfAvailable(userId, cost);
        if (updated == 0) {
            throw new BusinessException(ErrorCodeEnum.QUOTA_INSUFFICIENT);
        }
        String freezeId = UUID.randomUUID().toString().replace("-", "");
        QuotaFreeze freeze = new QuotaFreeze();
        freeze.setFreezeId(freezeId);
        freeze.setUserId(userId);
        freeze.setAmount(cost);
        freeze.setAbilityCode(abilityCode);
        freeze.setStatus(FREEZE_STATUS_PENDING);
        freeze.setRequestId(StringUtils.hasText(requestId) ? requestId : null);
        freeze.setCreatedAt(LocalDateTime.now());
        freeze.setUpdatedAt(LocalDateTime.now());
        quotaFreezeMapper.insert(freeze);
        appendLedger(userId, LEDGER_FREEZE, cost, freezeId, abilityCode, "quota freeze");
        return Map.of("freezeId", freezeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String freezeId) {
        QuotaFreeze freeze = quotaFreezeMapper.selectForUpdateByFreezeId(freezeId);
        if (freeze == null) {
            throw new BusinessException(ErrorCodeEnum.FREEZE_NOT_FOUND);
        }
        if (FREEZE_STATUS_CONFIRMED.equals(freeze.getStatus())) {
            return;
        }
        if (!FREEZE_STATUS_PENDING.equals(freeze.getStatus())) {
            throw new BusinessException("freeze is not pending");
        }
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(freeze.getUserId());
        if (quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        int amount = freeze.getAmount();
        int fromPeriod = Math.min(quota.getPeriodQuotaBalance(), amount);
        int fromTopup = amount - fromPeriod;
        quota.setPeriodQuotaBalance(Math.max(0, quota.getPeriodQuotaBalance() - fromPeriod));
        quota.setTopupQuotaBalance(Math.max(0, quota.getTopupQuotaBalance() - fromTopup));
        quota.setFrozenBalance(Math.max(0, quota.getFrozenBalance() - amount));
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);

        freeze.setStatus(FREEZE_STATUS_CONFIRMED);
        freeze.setUpdatedAt(LocalDateTime.now());
        quotaFreezeMapper.updateById(freeze);
        appendLedger(freeze.getUserId(), LEDGER_CONFIRM, -amount, freezeId, freeze.getAbilityCode(), "quota confirm");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(String freezeId) {
        QuotaFreeze freeze = quotaFreezeMapper.selectForUpdateByFreezeId(freezeId);
        if (freeze == null) {
            throw new BusinessException(ErrorCodeEnum.FREEZE_NOT_FOUND);
        }
        if (FREEZE_STATUS_RELEASED.equals(freeze.getStatus()) || FREEZE_STATUS_CONFIRMED.equals(freeze.getStatus())) {
            return;
        }
        int released = quotaAccountMapper.releaseFrozenBalance(freeze.getUserId(), freeze.getAmount());
        if (released == 0) {
            throw new BusinessException("quota release failed: frozen balance mismatch, freezeId=" + freezeId);
        }

        freeze.setStatus(FREEZE_STATUS_RELEASED);
        freeze.setUpdatedAt(LocalDateTime.now());
        quotaFreezeMapper.updateById(freeze);
        appendLedger(freeze.getUserId(), LEDGER_RELEASE, 0, freezeId, freeze.getAbilityCode(), "quota release");
    }

    @Override
    public List<String> listExpiredPendingFreezeIds(int timeoutMinutes, int batchLimit) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, timeoutMinutes));
        return quotaFreezeMapper.selectExpiredPendingFreezeIds(cutoff, Math.max(1, batchLimit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleBenefitEvent(TradeCompletedEvent event) {
        if (CommonConstant.EVENT_GROUP_BUY_REVOKED.equals(event.getEventType())) {
            handleGroupBuyRevoked(event);
            return;
        }
        if (CommonConstant.EVENT_GROUP_BUY_COMPLETED.equals(event.getEventType())) {
            handleGroupBuyCompleted(event);
            return;
        }
        throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "unknown benefit event type: " + event.getEventType());
    }

    /**
     * Monthly period-quota reset. Each member is processed in its OWN transaction
     * (row locks are not held across the whole batch), and the member row is locked
     * FOR UPDATE with the idempotency guard reading the locked row — so concurrent
     * runs (multi-instance cron, or a manual admin trigger overlapping the schedule)
     * cannot grant twice.
     */
    @Override
    public int grantMonthlyQuota() {
        String currentMonth = currentMonth();
        List<MemberAccount> members = memberAccountMapper.selectList(
                new LambdaQueryWrapper<MemberAccount>()
                        .eq(MemberAccount::getStatus, 1)
                        .select(MemberAccount::getUserId));
        int count = 0;
        for (MemberAccount candidate : members) {
            Long userId = candidate.getUserId();
            Boolean granted = transactionTemplate()
                    .execute(status -> grantMonthlyQuotaForUser(userId, currentMonth));
            if (Boolean.TRUE.equals(granted)) {
                count++;
            }
        }
        return count;
    }

    private boolean grantMonthlyQuotaForUser(Long userId, String currentMonth) {
        MemberAccount member = memberAccountMapper.selectForUpdateByUserId(userId);
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean expiredPro = "PRO".equals(member.getTier())
                && member.getExpireAt() != null && member.getExpireAt().isBefore(now);
        boolean alreadyGrantedThisMonth = currentMonth.equals(member.getLastPeriodGrantMonth());
        // Idempotent: nothing to do when this month is already granted and the tier is still valid.
        if (alreadyGrantedThisMonth && !expiredPro) {
            return false;
        }
        if (expiredPro) {
            member.setTier("FREE");
            member.setExpireAt(null);
        }
        int monthlyQuota = "PRO".equals(member.getTier()) ? resolveMonthlyQuota(userId) : FREE_MONTHLY_QUOTA;
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (quota == null) {
            return false;
        }
        quota.setPeriodQuotaBalance(monthlyQuota);
        quota.setUpdateTime(now);
        quotaAccountMapper.updateById(quota);
        member.setLastPeriodGrantMonth(currentMonth);
        member.setUpdateTime(now);
        memberAccountMapper.updateById(member);
        appendLedger(userId, LEDGER_MONTHLY_GRANT, monthlyQuota, null, null,
                expiredPro ? "pro expired -> free, quota reset" : "monthly period quota reset");
        return true;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = this.transactionTemplate;
        if (template == null) {
            template = new TransactionTemplate(transactionManager);
            this.transactionTemplate = template;
        }
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminAdjustQuota(Long userId, int periodDelta, int topupDelta, String remark) {
        QuotaAccount quota = requireQuota(userId);
        quota.setPeriodQuotaBalance(Math.max(0, quota.getPeriodQuotaBalance() + periodDelta));
        quota.setTopupQuotaBalance(Math.max(0, quota.getTopupQuotaBalance() + topupDelta));
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);
        appendLedger(userId, LEDGER_ADMIN_ADJUST, periodDelta + topupDelta, null, null, remark);
    }

    private void handleGroupBuyCompleted(TradeCompletedEvent event) {
        String idempotencyKey = event.getOrderId() + ":" + event.getEventType();
        if (benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>().eq(BenefitGrantEvent::getIdempotencyKey, idempotencyKey)) != null) {
            return;
        }
        // If a revoke already arrived for this order (out-of-order delivery), do not grant.
        BenefitGrantEvent revokeTombstone = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getOrderId, event.getOrderId())
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_REVOKED));
        if (revokeTombstone != null) {
            insertBenefitEvent(event, "SKIPPED_REVOKED", idempotencyKey, LocalDateTime.now(),
                    0, 0, 0, "NONE");
            appendLedger(event.getUserId(), LEDGER_REVOKE, 0, null, event.getProductCode(),
                    "grant skipped: order already revoked");
            return;
        }

        ProductSku sku = requireSku(event.getProductCode());
        String skuType = resolveSkuType(sku);
        if (SKU_TYPE_FREE.equals(skuType)) {
            throw new BusinessException("FREE SKU cannot be purchased");
        }

        MemberAccount member = requireMember(event.getUserId());
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(event.getUserId());
        LocalDateTime now = LocalDateTime.now();

        int memberDaysDelta = 0;
        int periodQuotaGranted = 0;
        int topupQuotaGranted = 0;
        String tierEffect = "NONE";
        // 阶梯拼团加赠额度：在 SKU 基础额度之上叠加发放（直购/经典为 0）
        int tierBonus = (event.getBonusQuota() != null && event.getBonusQuota() > 0) ? event.getBonusQuota() : 0;

        if (SKU_TYPE_MEMBER.equals(skuType)) {
            tierEffect = "PRO";
            member.setTier("PRO");
            if (member.getStartAt() == null) {
                member.setStartAt(now);
            }
            if (sku.getMemberDays() != null && sku.getMemberDays() > 0) {
                memberDaysDelta = sku.getMemberDays();
                LocalDateTime base = member.getExpireAt() != null && member.getExpireAt().isAfter(now)
                        ? member.getExpireAt() : now;
                member.setExpireAt(base.plusDays(memberDaysDelta));
            }
            member.setLastPeriodGrantMonth(currentMonth());
            if (sku.getPeriodQuota() != null && sku.getPeriodQuota() > 0) {
                periodQuotaGranted = sku.getPeriodQuota() + tierBonus;
                quota.setPeriodQuotaBalance(periodQuotaGranted);
                appendLedger(event.getUserId(), LEDGER_GRANT, periodQuotaGranted, null, sku.getCode(), "group buy grant");
            }
        } else if (SKU_TYPE_TOPUP.equals(skuType)) {
            if (sku.getTopupQuota() != null && sku.getTopupQuota() > 0) {
                topupQuotaGranted = sku.getTopupQuota() + tierBonus;
                quota.setTopupQuotaBalance(quota.getTopupQuotaBalance() + topupQuotaGranted);
                appendLedger(event.getUserId(), LEDGER_GRANT, topupQuotaGranted, null, sku.getCode(), "topup grant");
            }
        }

        member.setUpdateTime(now);
        memberAccountMapper.updateById(member);
        quota.setUpdateTime(now);
        quotaAccountMapper.updateById(quota);
        insertBenefitEvent(event, "GRANTED", idempotencyKey, now,
                memberDaysDelta, periodQuotaGranted, topupQuotaGranted, tierEffect);
    }

    private void handleGroupBuyRevoked(TradeCompletedEvent event) {
        String idempotencyKey = event.getOrderId() + ":" + event.getEventType();
        if (benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>().eq(BenefitGrantEvent::getIdempotencyKey, idempotencyKey)) != null) {
            return;
        }
        BenefitGrantEvent granted = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getOrderId, event.getOrderId())
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_COMPLETED)
                        .eq(BenefitGrantEvent::getStatus, "GRANTED"));
        if (granted == null) {
            // Revoke arrived before the grant was processed (out-of-order delivery).
            // Persist a REVOKED tombstone so the intent is not lost; handleGroupBuyCompleted
            // will detect it and refuse to grant, keeping the user on FREE after refund.
            LocalDateTime revokedAt = LocalDateTime.now();
            insertBenefitEvent(event, "REVOKED", idempotencyKey, revokedAt, 0, 0, 0, "REVOKE");
            appendLedger(event.getUserId(), LEDGER_REVOKE, 0, null, event.getProductCode(),
                    "group buy revoked before grant (tombstone)");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        MemberAccount member = requireMember(event.getUserId());
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(event.getUserId());

        int topupToRevoke = granted.getTopupQuotaGranted() == null ? 0 : granted.getTopupQuotaGranted();
        if (topupToRevoke > 0) {
            int actualRevoke = Math.min(topupToRevoke, quota.getTopupQuotaBalance());
            quota.setTopupQuotaBalance(quota.getTopupQuotaBalance() - actualRevoke);
            appendLedger(event.getUserId(), LEDGER_REVOKE, -actualRevoke, null, event.getProductCode(), "topup revoked");
        }

        if ("PRO".equals(granted.getTierEffect())) {
            int periodToRevoke = granted.getPeriodQuotaGranted() == null ? 0 : granted.getPeriodQuotaGranted();
            if (periodToRevoke > 0) {
                int actualRevoke = Math.min(periodToRevoke, quota.getPeriodQuotaBalance());
                quota.setPeriodQuotaBalance(quota.getPeriodQuotaBalance() - actualRevoke);
                appendLedger(event.getUserId(), LEDGER_REVOKE, -actualRevoke, null, event.getProductCode(),
                        "period quota revoked");
            }
            int days = granted.getMemberDaysDelta() == null ? 0 : granted.getMemberDaysDelta();
            if (days > 0 && member.getExpireAt() != null) {
                member.setExpireAt(member.getExpireAt().minusDays(days));
            }
            recalculateTier(member, now);
            if ("FREE".equals(member.getTier())) {
                quota.setPeriodQuotaBalance(FREE_MONTHLY_QUOTA);
            } else {
                int refreshed = resolveMonthlyQuota(member.getUserId());
                if (refreshed > 0) {
                    quota.setPeriodQuotaBalance(refreshed);
                }
            }
        }

        quota.setUpdateTime(now);
        quotaAccountMapper.updateById(quota);
        member.setUpdateTime(now);
        memberAccountMapper.updateById(member);

        granted.setStatus("REVOKED");
        benefitGrantEventMapper.updateById(granted);
        insertBenefitEvent(event, "REVOKED", idempotencyKey, now, 0, 0, 0, "REVOKE");
        appendLedger(event.getUserId(), LEDGER_REVOKE, 0, null, event.getProductCode(), "group buy revoked");
    }

    private void recalculateTier(MemberAccount member, LocalDateTime now) {
        if (member.getExpireAt() != null && member.getExpireAt().isAfter(now)) {
            member.setTier("PRO");
            return;
        }
        long activeProGrants = benefitGrantEventMapper.selectCount(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getUserId, member.getUserId())
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_COMPLETED)
                        .eq(BenefitGrantEvent::getStatus, "GRANTED")
                        .eq(BenefitGrantEvent::getTierEffect, "PRO"));
        if (activeProGrants > 0 && member.getExpireAt() != null && member.getExpireAt().isAfter(now)) {
            member.setTier("PRO");
        } else {
            member.setTier("FREE");
            if (member.getExpireAt() != null && !member.getExpireAt().isAfter(now)) {
                member.setExpireAt(null);
            }
        }
    }

    private String resolveSkuType(ProductSku sku) {
        if (sku.getSkuType() != null && !sku.getSkuType().isBlank()) {
            return sku.getSkuType();
        }
        if ("PRO".equalsIgnoreCase(sku.getTier()) && sku.getMemberDays() != null && sku.getMemberDays() > 0) {
            return SKU_TYPE_MEMBER;
        }
        if (sku.getTopupQuota() != null && sku.getTopupQuota() > 0
                && (sku.getMemberDays() == null || sku.getMemberDays() == 0)) {
            return SKU_TYPE_TOPUP;
        }
        return SKU_TYPE_FREE;
    }

    private int resolveMonthlyQuota(Long userId) {
        BenefitGrantEvent latestGrant = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getUserId, userId)
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_COMPLETED)
                        .eq(BenefitGrantEvent::getStatus, "GRANTED")
                        .orderByDesc(BenefitGrantEvent::getCreatedAt)
                        .last("LIMIT 1"));
        if (latestGrant == null) {
            return 500;
        }
        ProductSku sku = productSkuMapper.selectOne(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getCode, latestGrant.getProductCode()));
        if (sku == null || sku.getPeriodQuota() == null || sku.getPeriodQuota() <= 0) {
            return 500;
        }
        return sku.getPeriodQuota();
    }

    private void insertBenefitEvent(TradeCompletedEvent event, String status, String idempotencyKey,
                                    LocalDateTime now, int memberDaysDelta, int periodQuotaGranted,
                                    int topupQuotaGranted, String tierEffect) {
        BenefitGrantEvent grantEvent = new BenefitGrantEvent();
        grantEvent.setIdempotencyKey(idempotencyKey);
        grantEvent.setUserId(event.getUserId());
        grantEvent.setOrderId(event.getOrderId());
        grantEvent.setEventType(event.getEventType());
        grantEvent.setProductCode(event.getProductCode());
        grantEvent.setStatus(status);
        grantEvent.setMemberDaysDelta(memberDaysDelta);
        grantEvent.setPeriodQuotaGranted(periodQuotaGranted);
        grantEvent.setTopupQuotaGranted(topupQuotaGranted);
        grantEvent.setTierEffect(tierEffect);
        grantEvent.setCreatedAt(now);
        benefitGrantEventMapper.insert(grantEvent);
    }

    private void appendLedger(Long userId, String type, int amount, String freezeId, String abilityCode, String remark) {
        QuotaLedger ledger = new QuotaLedger();
        ledger.setUserId(userId);
        ledger.setType(type);
        ledger.setAmount(amount);
        ledger.setFreezeId(freezeId);
        ledger.setAbilityCode(abilityCode);
        ledger.setRemark(remark);
        ledger.setCreatedAt(LocalDateTime.now());
        quotaLedgerMapper.insert(ledger);
    }

    private MemberAccount requireMember(Long userId) {
        MemberAccount member = memberAccountMapper.selectOne(
                new LambdaQueryWrapper<MemberAccount>().eq(MemberAccount::getUserId, userId));
        if (member == null) {
            initFree(userId);
            member = memberAccountMapper.selectOne(
                    new LambdaQueryWrapper<MemberAccount>().eq(MemberAccount::getUserId, userId));
        }
        return member;
    }

    private QuotaAccount requireQuota(Long userId) {
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        return quota;
    }

    private ProductSku requireSku(String productCode) {
        ProductSku sku = productSkuMapper.selectOne(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getCode, productCode));
        if (sku == null) {
            throw new BusinessException("unknown product code: " + productCode);
        }
        return sku;
    }

    private String currentMonth() {
        return LocalDate.now().format(MONTH_FMT);
    }

    @Override
    public String benefitGrantStatusForOrder(String orderId) {
        BenefitGrantEvent completed = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getOrderId, orderId)
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_COMPLETED)
                        .orderByDesc(BenefitGrantEvent::getCreatedAt)
                        .last("LIMIT 1"));
        if (completed == null) {
            return "PENDING";
        }
        if ("REVOKED".equals(completed.getStatus())) {
            return "REVOKED";
        }
        if ("GRANTED".equals(completed.getStatus())) {
            return "GRANTED";
        }
        return "PENDING";
    }
}
