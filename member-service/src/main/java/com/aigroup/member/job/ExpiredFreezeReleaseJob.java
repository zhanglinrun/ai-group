package com.aigroup.member.job;

import com.aigroup.member.service.MemberService;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 过期冻结兜底释放任务。调度由 XXL-JOB admin 集中管理（cron: 0 0/5 * * * ?）。
 *
 * <p>调用方按「预扣(freeze)→确认(confirm)/释放(release)」两阶段计费。当旧式、无持久化结算所有者的
 * 调用链崩溃时，本任务分钟级扫描其 PENDING 僵尸冻结并逐个释放。由 agent-service 进程内结算扫描托管的冻结
 * 只告警、不自动释放，避免 provider 已经消耗而 confirm 尚在重试时被误释放。</p>
 */
@Slf4j
@Component
public class ExpiredFreezeReleaseJob {

    private final MemberService memberService;

    private final int timeoutMinutes;
    private final int batchLimit;

    public ExpiredFreezeReleaseJob(MemberService memberService,
                                   @Value("${ai-group.member.expired-freeze-timeout-minutes:30}") int timeoutMinutes,
                                   @Value("${ai-group.member.expired-freeze-batch-limit:200}") int batchLimit) {
        this.memberService = memberService;
        this.timeoutMinutes = timeoutMinutes;
        this.batchLimit = batchLimit;
    }

    @XxlJob("expiredFreezeReleaseJob")
    public void releaseExpiredFreezes() {
        List<String> managedFreezeIds = memberService.listExpiredManagedPendingFreezeIds(
                timeoutMinutes, batchLimit);
        if (managedFreezeIds != null && !managedFreezeIds.isEmpty()) {
            // agent-service owns the in-process settlement scan for these rows. Releasing
            // them here can race a CONFIRM retry after provider usage was observed.
            log.warn("managed quota freezes await durable settlement, count={}, sample={}",
                    managedFreezeIds.size(), managedFreezeIds.stream().limit(10).toList());
        }
        List<String> freezeIds = memberService.listExpiredPendingFreezeIds(timeoutMinutes, batchLimit);
        if (freezeIds == null || freezeIds.isEmpty()) {
            return;
        }
        int released = 0;
        int failures = 0;
        for (String freezeId : freezeIds) {
            try {
                // 跨 bean 调用，release 的 @Transactional 生效（每个 freeze 独立事务 + 行锁），且 release 自身幂等。
                memberService.release(freezeId);
                released++;
            } catch (Exception e) {
                log.warn("release expired quota freeze failed, freezeId={}", freezeId, e);
                failures++;
            }
        }
        log.info("expired quota freeze release completed, scanned={}, released={}", freezeIds.size(), released);
        if (failures > 0) {
            throw new IllegalStateException("expired freeze release failed count=" + failures);
        }
    }
}
