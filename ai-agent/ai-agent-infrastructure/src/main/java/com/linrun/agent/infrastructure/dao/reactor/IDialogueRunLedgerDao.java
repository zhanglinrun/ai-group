package com.linrun.agent.infrastructure.dao.reactor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunLeaseRenewalCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunCancelCommand;

import java.time.LocalDateTime;

import java.util.List;

/**
 * 对话执行总账 DAO。
 */
@Mapper
public interface IDialogueRunLedgerDao {

    int insertRun(DialogueRun run);

    int updateRunFinish(DialogueRun run);

    int updateRunHeartbeat(@Param("runId") Long runId,
                           @Param("requestId") String requestId,
                           @Param("heartbeatAt") LocalDateTime heartbeatAt);

    int renewRunLease(DialogueRunLeaseRenewalCommand command);

    int requestRunCancellation(DialogueRunCancelCommand command);

    int failWorkerLostRuns(DialogueRunRecoveryCommand command);

    DialogueRun queryByRequestId(@Param("requestId") String requestId);

    DialogueRun queryById(@Param("runId") Long runId);

    List<DialogueRunView> queryRecentBySessionId(@Param("sessionId") String sessionId,
                                                 @Param("limit") int limit);

    List<DialogueRunView> queryBySessionId(@Param("sessionId") String sessionId);
}
