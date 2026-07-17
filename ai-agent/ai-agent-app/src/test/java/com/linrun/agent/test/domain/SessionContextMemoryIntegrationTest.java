package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.service.execute.agentloop.AgentLoopExecuteStrategy;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.AgentRuntime;
import com.linrun.agent.domain.agent.runtime.AgentRuntimeOutcome;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.memory.ConversationMemoryManager;
import com.linrun.agent.domain.agent.memory.ConversationMemoryManagerImpl;
import com.linrun.agent.domain.agent.memory.LongTermMemoryService;
import com.linrun.agent.domain.agent.memory.SessionContextMemoryService;
import com.linrun.agent.infrastructure.reactor.service.impl.SessionContextMemoryServiceImpl;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单会话上下文记忆入口注入测试。
 */
public class SessionContextMemoryIntegrationTest {

    @Test
    public void shouldInjectHistoryDialogueBeforeUnifiedLoopExecution() throws Exception {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedSimpleHistory(ctx, "req-react-history-001", "session-react-history-001", "历史 thought from react");
        SessionContextMemoryServiceImpl memoryService = new SessionContextMemoryServiceImpl(
                ctx.queryService,
                ctx.llmDao,
                ctx.toolDao,
                ctx.artifactDao
        );

        AgentRuntime runner = Mockito.mock(AgentRuntime.class);
        Mockito.when(runner.runWithOutcome(Mockito.any(AgentRequest.class), Mockito.any()))
                .thenAnswer(invocation -> {
                    AgentRequest request = invocation.getArgument(0);
                    Assert.assertFalse(request.getHistoryDialogue().contains("历史 thought from react"));
                    Assert.assertTrue(request.getHistoryDialogue().contains("summary:req-react-history-001"));
                    Assert.assertTrue(request.getHistoryDialogue().contains("### Run req-react-history-001"));
                    Assert.assertTrue(request.getHistoryDialogue().contains(
                            "<untrusted-context source=\"retrieved-conversation-memory\">"));
                    return AgentRuntimeOutcome.executed("ok");
                });

        AgentLoopExecuteStrategy strategy = new AgentLoopExecuteStrategy(
                runner,
                new ReactorConfig(),
                memoryManager(memoryService)
        );

        AgentRequest request = AgentRequest.builder()
                .requestId("req-react-current-001")
                .sessionId("session-react-history-001")
                .query("当前 react 请求")
                .build();
        Printer printer = Mockito.mock(Printer.class);
        strategy.execute(request, printer);

        Assert.assertFalse(request.getHistoryDialogue().contains("历史 thought from react"));
        Assert.assertTrue(request.getHistoryDialogue().contains("summary:req-react-history-001"));
        Mockito.verify(runner).runWithOutcome(request, printer);
    }

    @Test
    public void shouldPassProtocolNeutralPrinterToRuntime() throws Exception {
        Printer printer = Mockito.mock(Printer.class);
        AgentRuntime runner = Mockito.mock(AgentRuntime.class);
        Mockito.when(runner.runWithOutcome(Mockito.any(AgentRequest.class), Mockito.any()))
                .thenAnswer(invocation -> {
                    Object runtimePrinter = invocation.getArgument(1);
                    Assert.assertNotNull(runtimePrinter);
                    Assert.assertSame(printer, runtimePrinter);
                    return AgentRuntimeOutcome.executed("ok");
                });

        AgentLoopExecuteStrategy strategy = new AgentLoopExecuteStrategy(
                runner,
                new ReactorConfig(),
                Mockito.mock(ConversationMemoryManager.class)
        );

        AgentRequest request = AgentRequest.builder()
                .requestId("req-react-current-002")
                .sessionId("session-react-history-002")
                .query("当前 react 请求")
                .agentType(AgentType.AGENT_LOOP.getValue())
                .executionMode("STANDARD")
                .build();

        strategy.execute(request, printer);
    }

    @Test
    public void shouldNotPersistLongTermMemoryForFinishedRunReplay() throws Exception {
        Printer printer = Mockito.mock(Printer.class);
        AgentRuntime runner = Mockito.mock(AgentRuntime.class);
        ConversationMemoryManager memoryManager = Mockito.mock(ConversationMemoryManager.class);
        Mockito.when(runner.runWithOutcome(Mockito.any(AgentRequest.class), Mockito.any()))
                .thenReturn(AgentRuntimeOutcome.notExecuted("replayed answer"));

        AgentLoopExecuteStrategy strategy = new AgentLoopExecuteStrategy(
                runner,
                new ReactorConfig(),
                memoryManager
        );
        AgentRequest request = AgentRequest.builder()
                .requestId("req-finished-replay-memory")
                .sessionId("session-finished-replay-memory")
                .ownerId("1001")
                .query("请记住我的偏好")
                .executionMode("STANDARD")
                .build();

        strategy.execute(request, printer);

        Mockito.verify(memoryManager, Mockito.never()).persistTurnAsync(Mockito.any());
    }

    /**
     * 构造仅启用中期(会话)记忆的管理器：长期记忆 mock 为空召回，ReactorConfig 默认关闭长期开关，
     * 因此 assembleHistoryBlock 等价于直接返回会话历史，保持本用例校验"执行前注入历史"的原意。
     */
    private ConversationMemoryManager memoryManager(SessionContextMemoryService medium) {
        LongTermMemoryService longTerm = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(longTerm.recall(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());
        return new ConversationMemoryManagerImpl(medium, longTerm, new ReactorConfig());
    }

    private void seedSimpleHistory(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                                   String requestId,
                                   String sessionId,
                                   String thought) {
        Long runId = ctx.recorder.createRun(com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord.builder()
                .runUid(requestId)
                .requestId(requestId)
                .sessionId(sessionId)
                .entryAgent(com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD)
                .queryText("query:" + requestId)
                .startedAt(LocalDateTime.of(2026, 5, 4, 12, 0))
                .build());
        Long llmInvocationId = ctx.recorder.createLlmInvocation(com.linrun.agent.domain.agent.ledger.model.LlmInvocationStartRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .invocationSeq(1)
                .agentName("react")
                .stepNo(1)
                .callKind(com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants.CALL_KIND_ASK_TOOL)
                .streaming(false)
                .modelName("test-model")
                .startedAt(LocalDateTime.of(2026, 5, 4, 12, 1))
                .build());
        ctx.recorder.finishLlmInvocation(com.linrun.agent.domain.agent.ledger.model.LlmInvocationFinishRecord.builder()
                .llmInvocationId(llmInvocationId)
                .requestId(requestId)
                .status(com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS)
                .responseText(thought)
                .toolCallCount(0)
                .promptTokens(8)
                .completionTokens(9)
                .totalTokens(17)
                .finishReason("stop")
                .finishedAt(LocalDateTime.of(2026, 5, 4, 12, 1, 30))
                .build());
        ctx.recorder.finishRun(com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .status(com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("summary:" + requestId)
                .finishedAt(LocalDateTime.of(2026, 5, 4, 12, 2))
                .build());
    }
}
