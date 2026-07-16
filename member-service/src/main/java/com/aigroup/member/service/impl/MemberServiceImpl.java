package com.aigroup.member.service.impl;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.common.constant.ErrorCodeEnum;
import com.aigroup.common.exception.BusinessException;
import com.aigroup.member.dto.TradeCompletedEvent;
import com.aigroup.member.entity.BenefitGrantEvent;
import com.aigroup.member.entity.ProductSku;
import com.aigroup.member.entity.QuotaAccount;
import com.aigroup.member.entity.QuotaFreeze;
import com.aigroup.member.entity.QuotaLedger;
import com.aigroup.member.mapper.BenefitGrantEventMapper;
import com.aigroup.member.mapper.ProductSkuMapper;
import com.aigroup.member.mapper.QuotaAccountMapper;
import com.aigroup.member.mapper.QuotaFreezeMapper;
import com.aigroup.member.mapper.QuotaLedgerMapper;
import com.aigroup.member.service.MemberService;
import com.aigroup.member.vo.MemberSummaryVO;
import com.aigroup.member.vo.QuotaLedgerVO;
import com.aigroup.member.vo.SkuVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    public static final long MICRO_PER_CREDIT = 1_000_000L;
    public static final long FREE_MONTHLY_QUOTA = 5L * MICRO_PER_CREDIT;

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
    private final QuotaAccountMapper quotaAccountMapper;
    private final QuotaFreezeMapper quotaFreezeMapper;
    private final BenefitGrantEventMapper benefitGrantEventMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final PlatformTransactionManager transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initFree(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "userId is required");
        }
        if (quotaAccountMapper.insertInitialAccount(userId, FREE_MONTHLY_QUOTA, currentMonth()) == 1) {
            appendLedger(userId, LEDGER_GRANT, FREE_MONTHLY_QUOTA, null, "free", "initial monthly free quota");
        }
    }

    @Override
    public List<SkuVO> listSkus() {
        return productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getStatus, 1)
        ).stream().map(sku -> {
            SkuVO vo = new SkuVO();
            BeanUtils.copyProperties(sku, vo);
            return vo;
        }).toList();
    }

    @Override
    public SkuVO findEnabledSkuByGoodsId(String goodsId) {
        ProductSku sku = productSkuMapper.selectOne(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getGroupGoodsId, goodsId)
                        .eq(ProductSku::getStatus, 1)
                        .last("LIMIT 1"));
        if (sku == null) {
            throw new BusinessException("enabled quota package not found for goodsId: " + goodsId);
        }
        SkuVO vo = new SkuVO();
        BeanUtils.copyProperties(sku, vo);
        return vo;
    }

    @Override
    public MemberSummaryVO summary(Long userId) {
        QuotaAccount quota = quotaAccountMapper.selectOne(
                new LambdaQueryWrapper<QuotaAccount>().eq(QuotaAccount::getUserId, userId));
        if (quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        MemberSummaryVO vo = new MemberSummaryVO();
        vo.setUserId(userId);
        vo.setFreeQuotaBalance(quota.getFreeQuotaBalance());
        vo.setPaidQuotaBalance(quota.getPaidQuotaBalance());
        vo.setFrozenBalance(quota.getFrozenBalance());
        vo.setAvailableQuota(Math.max(0L, subtractExact(totalBalance(quota), quota.getFrozenBalance())));
        return vo;
    }

    @Override
    public List<QuotaLedgerVO> listQuotaLedger(Long userId) {
        return quotaLedgerMapper.selectList(
                new QueryWrapper<QuotaLedger>()
                        .eq("user_id", userId)
                        .orderByDesc("created_at")
                        .last("LIMIT 50")
        ).stream().map(row -> {
            QuotaLedgerVO vo = new QuotaLedgerVO();
            BeanUtils.copyProperties(row, vo);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> freeze(Long userId, long requestedAmount, long minAmount,
                                      String abilityCode, String requestId) {
        if (requestedAmount <= 0 || minAmount <= 0 || minAmount > requestedAmount) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR,
                    "amount must satisfy requestedAmount >= minAmount > 0");
        }
        if (StringUtils.hasText(requestId)) {
            QuotaFreeze existing = quotaFreezeMapper.selectByUserIdAndRequestId(userId, requestId);
            if (existing != null) {
                return reusePendingFreeze(existing);
            }
        }

        QuotaAccount locked = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (locked == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        if (StringUtils.hasText(requestId)) {
            QuotaFreeze concurrentExisting = quotaFreezeMapper
                    .selectForUpdateByUserIdAndRequestId(userId, requestId);
            if (concurrentExisting != null) {
                return reusePendingFreeze(concurrentExisting);
            }
        }

        long pendingFree = quotaFreezeMapper.sumPendingFreeAmount(userId);
        long pendingPaid = quotaFreezeMapper.sumPendingPaidAmount(userId);
        long freeAvailable = Math.max(0L, subtractExact(locked.getFreeQuotaBalance(), pendingFree));
        long paidAvailable = Math.max(0L, subtractExact(locked.getPaidQuotaBalance(), pendingPaid));
        long available = addExact(freeAvailable, paidAvailable);
        long amount = Math.min(requestedAmount, available);
        if (amount < minAmount) {
            throw new BusinessException(ErrorCodeEnum.QUOTA_INSUFFICIENT);
        }
        long freeAmount = Math.min(freeAvailable, amount);
        long paidAmount = amount - freeAmount;
        if (quotaAccountMapper.freezeBalanceIfAvailable(userId, amount) == 0) {
            throw new BusinessException(ErrorCodeEnum.QUOTA_INSUFFICIENT);
        }

        LocalDateTime now = LocalDateTime.now();
        QuotaFreeze freeze = new QuotaFreeze();
        freeze.setFreezeId(UUID.randomUUID().toString().replace("-", ""));
        freeze.setUserId(userId);
        freeze.setAmount(amount);
        freeze.setFreeAmount(freeAmount);
        freeze.setPaidAmount(paidAmount);
        freeze.setSettledAmount(0L);
        freeze.setAbilityCode(StringUtils.hasText(abilityCode) ? abilityCode : "llm");
        freeze.setStatus(FREEZE_STATUS_PENDING);
        freeze.setRequestId(StringUtils.hasText(requestId) ? requestId : null);
        freeze.setCreatedAt(now);
        freeze.setUpdatedAt(now);
        quotaFreezeMapper.insert(freeze);
        appendLedger(userId, LEDGER_FREEZE, amount, freeze.getFreezeId(), freeze.getAbilityCode(), "quota reserved");
        return freezeResult(freeze);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String freezeId) {
        confirm(freezeId, -1L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String freezeId, long requestedActualAmount) {
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
        long actualAmount = requestedActualAmount < 0 ? freeze.getAmount() : requestedActualAmount;
        if (actualAmount < 0 || actualAmount > freeze.getAmount()) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR,
                    "actualAmount must be between 0 and reserved amount");
        }

        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(freeze.getUserId());
        if (quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        long fromFree = Math.min(actualAmount, freeze.getFreeAmount());
        long fromPaid = actualAmount - fromFree;
        if (quota.getFreeQuotaBalance() < fromFree || quota.getPaidQuotaBalance() < fromPaid
                || quota.getFrozenBalance() < freeze.getAmount()) {
            throw new BusinessException("quota settlement failed: balance mismatch, freezeId=" + freezeId);
        }
        quota.setFreeQuotaBalance(quota.getFreeQuotaBalance() - fromFree);
        quota.setPaidQuotaBalance(quota.getPaidQuotaBalance() - fromPaid);
        quota.setFrozenBalance(quota.getFrozenBalance() - freeze.getAmount());
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);

        freeze.setSettledAmount(actualAmount);
        freeze.setStatus(FREEZE_STATUS_CONFIRMED);
        freeze.setUpdatedAt(LocalDateTime.now());
        quotaFreezeMapper.updateById(freeze);
        appendLedger(freeze.getUserId(), LEDGER_CONFIRM, -actualAmount, freezeId,
                freeze.getAbilityCode(), "quota settled; unused reservation released");
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
        if (quotaAccountMapper.releaseFrozenBalance(freeze.getUserId(), freeze.getAmount()) == 0) {
            throw new BusinessException("quota release failed: frozen balance mismatch, freezeId=" + freezeId);
        }
        freeze.setSettledAmount(0L);
        freeze.setStatus(FREEZE_STATUS_RELEASED);
        freeze.setUpdatedAt(LocalDateTime.now());
        quotaFreezeMapper.updateById(freeze);
        appendLedger(freeze.getUserId(), LEDGER_RELEASE, 0L, freezeId, freeze.getAbilityCode(), "reservation released");
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
        } else if (CommonConstant.EVENT_GROUP_BUY_COMPLETED.equals(event.getEventType())) {
            handleGroupBuyCompleted(event);
        } else {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR,
                    "unknown benefit event type: " + event.getEventType());
        }
    }

    @Override
    public int grantMonthlyQuota() {
        String month = currentMonth();
        List<QuotaAccount> accounts = quotaAccountMapper.selectList(
                new LambdaQueryWrapper<QuotaAccount>().select(QuotaAccount::getUserId));
        int count = 0;
        for (QuotaAccount candidate : accounts) {
            Boolean granted = transactionTemplate().execute(
                    status -> grantMonthlyQuotaForUser(candidate.getUserId(), month));
            if (Boolean.TRUE.equals(granted)) {
                count++;
            }
        }
        return count;
    }

    boolean grantMonthlyQuotaForUser(Long userId, String month) {
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (quota == null || month.equals(quota.getLastFreeGrantMonth())) {
            return false;
        }
        long delta = FREE_MONTHLY_QUOTA - quota.getFreeQuotaBalance();
        quota.setFreeQuotaBalance(FREE_MONTHLY_QUOTA);
        quota.setLastFreeGrantMonth(month);
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);
        appendLedger(userId, LEDGER_MONTHLY_GRANT, delta, null, "free", "monthly free quota reset");
        return true;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = transactionTemplate;
        if (template == null) {
            template = new TransactionTemplate(transactionManager);
            transactionTemplate = template;
        }
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminAdjustQuota(Long userId, long paidDelta, String remark) {
        if (!StringUtils.hasText(remark)) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "remark is required");
        }
        QuotaAccount quota = requireQuota(userId);
        long paidMicroDelta = creditsToMicro(paidDelta);
        long adjusted = addExact(quota.getPaidQuotaBalance(), paidMicroDelta);
        long reservedPaid = quotaFreezeMapper.sumPendingPaidAmount(userId);
        if (adjusted < reservedPaid) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR,
                    "paid quota cannot become lower than pending reservations");
        }
        quota.setPaidQuotaBalance(adjusted);
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);
        appendLedger(userId, LEDGER_ADMIN_ADJUST, paidMicroDelta, null, "admin", remark.trim());
    }

    private void handleGroupBuyCompleted(TradeCompletedEvent event) {
        String idempotencyKey = idempotencyKey(event);
        if (findBenefitByKey(idempotencyKey) != null) {
            return;
        }
        BenefitGrantEvent revokeTombstone = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getOrderId, event.getOrderId())
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_REVOKED));
        if (revokeTombstone != null) {
            insertBenefitEvent(event, "SKIPPED_REVOKED", idempotencyKey, 0L);
            appendLedger(event.getUserId(), LEDGER_REVOKE, 0L, null, event.getProductCode(),
                    "grant skipped: order already revoked");
            return;
        }
        if (event.getBaseQuota() == null || event.getBaseQuota() <= 0) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "baseQuota snapshot must be positive");
        }
        long bonus = event.getBonusQuota() == null ? 0L : event.getBonusQuota();
        if (bonus < 0) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "bonusQuota cannot be negative");
        }
        long granted = creditsToMicro(addExact(event.getBaseQuota(), bonus));
        QuotaAccount quota = requireQuota(event.getUserId());
        quota.setPaidQuotaBalance(addExact(quota.getPaidQuotaBalance(), granted));
        quota.setUpdateTime(LocalDateTime.now());
        quotaAccountMapper.updateById(quota);
        appendLedger(event.getUserId(), LEDGER_GRANT, granted, null, event.getProductCode(),
                "paid quota granted from order snapshot");
        insertBenefitEvent(event, "GRANTED", idempotencyKey, granted);
    }

    private void handleGroupBuyRevoked(TradeCompletedEvent event) {
        String idempotencyKey = idempotencyKey(event);
        if (findBenefitByKey(idempotencyKey) != null) {
            return;
        }
        BenefitGrantEvent granted = benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>()
                        .eq(BenefitGrantEvent::getOrderId, event.getOrderId())
                        .eq(BenefitGrantEvent::getEventType, CommonConstant.EVENT_GROUP_BUY_COMPLETED)
                        .eq(BenefitGrantEvent::getStatus, "GRANTED"));
        if (granted == null) {
            insertBenefitEvent(event, "REVOKED", idempotencyKey, 0L);
            appendLedger(event.getUserId(), LEDGER_REVOKE, 0L, null, event.getProductCode(),
                    "order revoked before quota grant");
            return;
        }
        insertBenefitEvent(event, "REJECTED_GRANTED", idempotencyKey, 0L);
        appendLedger(event.getUserId(), LEDGER_REVOKE, 0L, null, event.getProductCode(),
                "automatic revoke rejected: quota was already granted; admin review required");
    }

    private BenefitGrantEvent findBenefitByKey(String key) {
        return benefitGrantEventMapper.selectOne(
                new LambdaQueryWrapper<BenefitGrantEvent>().eq(BenefitGrantEvent::getIdempotencyKey, key));
    }

    private void insertBenefitEvent(TradeCompletedEvent event, String status,
                                    String idempotencyKey, long grantedQuota) {
        BenefitGrantEvent row = new BenefitGrantEvent();
        row.setIdempotencyKey(idempotencyKey);
        row.setUserId(event.getUserId());
        row.setOrderId(event.getOrderId());
        row.setEventType(event.getEventType());
        row.setProductCode(event.getProductCode());
        row.setStatus(status);
        row.setGrantedQuota(grantedQuota);
        row.setCreatedAt(LocalDateTime.now());
        benefitGrantEventMapper.insert(row);
    }

    private String idempotencyKey(TradeCompletedEvent event) {
        if (event == null || event.getUserId() == null || !StringUtils.hasText(event.getOrderId())
                || !StringUtils.hasText(event.getEventType()) || !StringUtils.hasText(event.getProductCode())) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR,
                    "userId, orderId, eventType and productCode are required");
        }
        return event.getOrderId() + ":" + event.getEventType();
    }

    private Map<String, Object> freezeResult(QuotaFreeze freeze) {
        return Map.of("freezeId", freeze.getFreezeId(), "amount", freeze.getAmount());
    }

    private Map<String, Object> reusePendingFreeze(QuotaFreeze freeze) {
        if (!FREEZE_STATUS_PENDING.equals(freeze.getStatus())) {
            throw new BusinessException("requestId was already settled");
        }
        return freezeResult(freeze);
    }

    private void appendLedger(Long userId, String type, long amount,
                              String freezeId, String abilityCode, String remark) {
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

    private QuotaAccount requireQuota(Long userId) {
        QuotaAccount quota = quotaAccountMapper.selectForUpdateByUserId(userId);
        if (quota == null) {
            throw new BusinessException(ErrorCodeEnum.MEMBER_NOT_FOUND);
        }
        return quota;
    }

    private long totalBalance(QuotaAccount quota) {
        return addExact(quota.getFreeQuotaBalance(), quota.getPaidQuotaBalance());
    }

    private long creditsToMicro(long credits) {
        try {
            return Math.multiplyExact(credits, MICRO_PER_CREDIT);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "quota amount overflow");
        }
    }

    private long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "quota amount overflow");
        }
    }

    private long subtractExact(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCodeEnum.PARAM_ERROR, "quota amount overflow");
        }
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
        return "GRANTED".equals(completed.getStatus()) ? "GRANTED" : "PENDING";
    }
}
