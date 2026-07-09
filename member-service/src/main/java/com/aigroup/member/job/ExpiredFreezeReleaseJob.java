package com.aigroup.member.job;

import com.aigroup.member.service.MemberService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 过期冻结兜底释放任务。
 *
 * <p>Agent 对话按「预扣(freeze)→确认(confirm)/释放(release)」两阶段计费。当进程崩溃或发布重启导致
 * 异步执行链丢失时，freeze 会永久停留在 PENDING，占用用户可用额度（available = period + topup − frozen）
 * 造成资损。本任务分钟级扫描超时仍 PENDING 的僵尸冻结并逐个释放（release 幂等、按 freeze 独立事务）。</p>
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

    @Scheduled(cron = "${ai-group.member.expired-freeze-release-cron:0 0/5 * * * ?}")
    public void releaseExpiredFreezes() {
        List<String> freezeIds = memberService.listExpiredPendingFreezeIds(timeoutMinutes, batchLimit);
        if (freezeIds == null || freezeIds.isEmpty()) {
            return;
        }
        int released = 0;
        for (String freezeId : freezeIds) {
            try {
                // 跨 bean 调用，release 的 @Transactional 生效（每个 freeze 独立事务 + 行锁），且 release 自身幂等。
                memberService.release(freezeId);
                released++;
            } catch (Exception e) {
                log.warn("release expired quota freeze failed, freezeId={}", freezeId, e);
            }
        }
        log.info("expired quota freeze release completed, scanned={}, released={}", freezeIds.size(), released);
    }
}
