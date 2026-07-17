package com.linrun.agent.infrastructure.dao.reactor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionUpsertRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;

import java.util.List;

/**
 * 会话主表 DAO。
 */
@Mapper
public interface IDialogueSessionLedgerDao {

    int upsertSession(DialogueSessionUpsertRecord record);

    int reconcileWorkerLostSessionHeads(@Param("errorCode") String errorCode);

    int softDeleteSession(@Param("ownerId") String ownerId,
                          @Param("sessionId") String sessionId);

    DialogueSession queryBySessionId(@Param("sessionId") String sessionId);

    DialogueSessionView querySessionView(@Param("sessionId") String sessionId);

    List<DialogueSessionView> queryRecentSessions(@Param("limit") int limit);

    DialogueSessionView querySessionViewByOwner(@Param("ownerId") String ownerId,
                                                @Param("sessionId") String sessionId);

    List<DialogueSessionView> queryRecentSessionsByOwner(@Param("ownerId") String ownerId,
                                                         @Param("limit") int limit);
}
