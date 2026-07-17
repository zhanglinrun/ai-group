package com.linrun.agent.infrastructure.dao.reactor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.linrun.agent.domain.agent.ledger.entity.LlmInvocation;

import java.util.List;

/**
 * LLM 调用账本 DAO。
 */
@Mapper
public interface ILlmInvocationLedgerDao {

    int insertLlmInvocation(LlmInvocation invocation);

    int updateLlmInvocationFinish(LlmInvocation invocation);

    List<LlmInvocation> queryByRunId(@Param("runId") Long runId);

    List<LlmInvocation> queryByRunIds(@Param("runIds") List<Long> runIds);
}
