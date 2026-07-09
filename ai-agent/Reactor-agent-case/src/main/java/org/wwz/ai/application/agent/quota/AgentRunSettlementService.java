package org.wwz.ai.application.agent.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueRun;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;

/**
 * 以执行账本 run 的终态作为配额结算依据。
 * dispatch 正常返回并不代表本次执行成功：ReactImplAgent / SummaryAgent / PlanSolve
 * 的失败分支会吞掉异常正常返回，此时账本 run 终态才是唯一可信的成败事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunSettlementService {

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;

    /**
     * dispatch 正常返回后判断本次 run 是否应按失败结算（释放冻结额度）。
     * 仅在账本明确记录 FAILED / TIMEOUT / STOPPED 终态时返回 true；
     * run 不存在（如 workflow / chat 链路不写账本）、仍为 RUNNING 或查询异常时，
     * 保持既有行为按成功结算，避免账本故障导致漏扣。
     */
    public boolean shouldReleaseAfterDispatch(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return false;
        }
        try {
            DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(requestId);
            if (run == null || run.getStatus() == null) {
                return false;
            }
            int status = run.getStatus();
            boolean release = status == ExecutionLedgerConstants.STATUS_FAILED
                    || status == ExecutionLedgerConstants.STATUS_TIMEOUT
                    || status == ExecutionLedgerConstants.STATUS_STOPPED;
            if (release) {
                log.info("{} run terminal status={} -> release quota freeze", requestId, status);
            }
            return release;
        } catch (Exception e) {
            log.warn("{} query run terminal status failed, fallback to confirm", requestId, e);
            return false;
        }
    }
}
