import { describe, expect, it, vi } from 'vitest';

import {
  applyGuardError,
  applyRunRecoveryPending,
  applyTerminalRunError,
  canRegenerateChat,
  createDurableRecoveryRequest,
  createRecoveryBudgetTracker,
  createRecoveryTimerController,
  createReplayEventDeduper,
  createStreamLifecycleController,
  isAgentRunBlockingInput,
  isFatalTransportRecoveryError,
  isTransportRecoveryBudgetExhausted,
  MAX_RUN_RECOVERY_ATTEMPTS,
  MAX_TRANSPORT_RECOVERY_ATTEMPTS,
  RUN_RECOVERY_DEADLINE_MILLIS,
  resolveAuthoritativeResultStatus,
  resolveRunRecoveryDirective,
  resolveTerminalRunState,
  resolveTransportRecoveryDelay,
  resetChatForAuthoritativeReplay,
  shouldResetForAuthoritativeReplay,
  TRANSPORT_RECOVERY_DEADLINE_MILLIS,
} from './useConversationStream';
import { resolveActionPanelVisibility } from './streamState';
import { parseAgentAnswer } from '@/utils/sseParsers';

describe('useConversationStream helpers', () => {
  it('guard error 应将当前 chat 标记为 FAILED 并生成 conclusion', () => {
    const currentChat = {
      requestId: 'req-1',
      loading: true,
      multiAgent: { tasks: [] },
      metrics: {},
    } as unknown as CHAT.ChatItem;

    const next = applyGuardError(currentChat, '当前请求处理失败，请稍后重试');

    expect(next.loading).toBe(false);
    expect(next.metrics?.status).toBe('FAILED');
    expect(next.conclusion?.messageType).toBe('result');
  });

  it('事件内 FAILED 应覆盖旧后端顶层 success', () => {
    const state = resolveTerminalRunState({ status: 'success', finished: true, errorMsg: '' }, {
      resultMap: {
        messageType: 'result',
        finish: true,
        resultMap: {
          runStatus: 'FAILED',
          errorMsg: '质量评估未通过',
        },
      },
    } as unknown as MESSAGE.EventData);

    expect(state).toEqual({ status: 'FAILED', message: '质量评估未通过' });
  });

  it('finished 但没有明确成功状态时不得推断为 SUCCESS', () => {
    const state = resolveTerminalRunState({ status: '', finished: true, errorMsg: '' });
    expect(state.status).toBe('RUNNING');
  });

  it('result 不得在缺少 run_finished 时把运行升级为 SUCCESS', () => {
    expect(
      resolveAuthoritativeResultStatus(
        { agentRun: { status: 'RUNNING', todos: [] } },
        'SUCCESS',
      ),
    ).toBe('FAILED');
  });

  it('result 只能确认已通过 completion gate 的 run_finished SUCCESS', () => {
    expect(
      resolveAuthoritativeResultStatus(
        {
          agentRun: {
            status: 'SUCCESS',
            todos: [],
            terminalEventSeen: true,
            completionGatePassed: true,
          },
        },
        'SUCCESS',
      ),
    ).toBe('SUCCESS');
  });

  it('result 不得覆盖 run_finished 的失败结论', () => {
    expect(
      resolveAuthoritativeResultStatus(
        {
          agentRun: {
            status: 'FAILED',
            todos: [],
            terminalEventSeen: true,
            completionGatePassed: false,
          },
        },
        'SUCCESS',
      ),
    ).toBe('FAILED');
  });

  it('STOPPED 终态应保留停止语义且生成非成功 conclusion', () => {
    const next = applyTerminalRunError(
      {
        requestId: 'req-stop-1',
        loading: true,
        multiAgent: { tasks: [] },
        metrics: {},
      } as unknown as CHAT.ChatItem,
      'STOPPED',
      '',
    );

    expect(next.forceStop).toBe(true);
    expect(next.metrics?.status).toBe('STOPPED');
    expect(next.conclusion?.result).toContain('任务已停止');
  });

  it('只有 canonical RUNNING 状态会阻塞新输入', () => {
    expect(isAgentRunBlockingInput('RUNNING')).toBe(true);
    expect(isAgentRunBlockingInput(' running ')).toBe(true);
    expect(isAgentRunBlockingInput('PAUSED')).toBe(false);
    expect(isAgentRunBlockingInput('SUCCESS')).toBe(false);
  });

  it('没有 renderable tool 时不应打开右侧工作区', () => {
    expect(resolveActionPanelVisibility({ taskList: [] })).toBe(false);
  });

  it('RUN_ALREADY_IN_PROGRESS 应按后端 retryAfterMillis 使用原请求恢复', () => {
    const directive = resolveRunRecoveryDirective(
      { resultMap: {}, errorMsg: '' },
      {
        resultMap: {
          messageType: 'run_finished',
          stopReason: 'RUN_ALREADY_IN_PROGRESS',
          retryable: true,
          retryAfterMillis: 1_250,
          existingRunId: 'req-running-1',
        },
      } as unknown as MESSAGE.EventData,
      0,
    );

    expect(directive).toEqual({
      retry: true,
      exhausted: false,
      retryable: true,
      delayMillis: 1_250,
    });
  });

  it('RUN_ALREADY_IN_PROGRESS 应使用全局退避且受 deadline 限制', () => {
    const secondAttempt = resolveRunRecoveryDirective(
      {
        resultMap: {
          stopReason: 'RUN_ALREADY_IN_PROGRESS',
          retryable: true,
          retryAfterMillis: 250,
        },
        errorMsg: '',
      },
      undefined,
      1,
      500,
    );
    const deadlineReached = resolveRunRecoveryDirective(
      {
        resultMap: {
          stopReason: 'RUN_ALREADY_IN_PROGRESS',
          retryable: true,
        },
        errorMsg: '',
      },
      undefined,
      1,
      RUN_RECOVERY_DEADLINE_MILLIS,
    );

    expect(secondAttempt?.delayMillis).toBe(2_000);
    expect(deadlineReached).toMatchObject({ retry: false, exhausted: true, retryable: true });
  });

  it('RUN_ALREADY_IN_PROGRESS 达到恢复上限后保持可恢复状态而不是伪造 STOPPED', () => {
    const directive = resolveRunRecoveryDirective(
      {
        resultMap: {
          stopReason: 'RUN_ALREADY_IN_PROGRESS',
          retryable: true,
          retryAfterMillis: 1_000,
        },
        errorMsg: '',
      },
      undefined,
      MAX_RUN_RECOVERY_ATTEMPTS,
    );

    expect(directive).toEqual({
      retry: false,
      exhausted: true,
      retryable: true,
      delayMillis: 0,
    });

    const pending = applyRunRecoveryPending(
      {
        requestId: 'req-running-1',
        loading: true,
        forceStop: false,
        multiAgent: { tasks: [] },
        tasks: [],
        metrics: { status: 'RUNNING' },
        agentRun: { status: 'RUNNING', todos: [] },
      } as unknown as CHAT.ChatItem,
      '仍在后台执行',
    );

    expect(pending.loading).toBe(false);
    expect(pending.forceStop).toBe(false);
    expect(pending.agentRun?.status).toBe('RUNNING');
    expect(pending.metrics?.status).toBe('RUNNING');
    expect(canRegenerateChat(pending)).toBe(false);
    expect(pending.conclusion).toBeUndefined();
  });

  it('RUN_ALREADY_IN_PROGRESS retryable=false 不应进入自动恢复', () => {
    const directive = resolveRunRecoveryDirective(
      {
        resultMap: {
          stopReason: 'RUN_ALREADY_IN_PROGRESS',
          retryable: false,
          retryAfterMillis: 1_000,
        },
        errorMsg: '',
      },
      undefined,
      0,
    );

    expect(directive).toEqual({
      retry: false,
      exhausted: false,
      retryable: false,
      delayMillis: 0,
    });
  });

  it.each(['RUN_OWNER_MISMATCH', 'RUN_REQUEST_MISMATCH'])(
    '%s 属于不可恢复 claim，前端不应进入同 requestId 重试循环',
    (stopReason) => {
      const directive = resolveRunRecoveryDirective(
        {
          resultMap: { stopReason, retryable: false },
          errorMsg: '',
        },
        undefined,
        0,
      );

      expect(directive).toBeNull();
    },
  );

  it('传输恢复应使用 400/800/1600ms 指数退避并在上限停止', () => {
    const transientError = new Error('network connection reset');

    expect(resolveTransportRecoveryDelay(transientError, 0)).toBe(400);
    expect(resolveTransportRecoveryDelay(transientError, 1)).toBe(800);
    expect(resolveTransportRecoveryDelay(transientError, 2)).toBe(1_600);
    expect(resolveTransportRecoveryDelay(transientError, MAX_TRANSPORT_RECOVERY_ATTEMPTS)).toBeNull();
    expect(
      resolveTransportRecoveryDelay(
        transientError,
        0,
        TRANSPORT_RECOVERY_DEADLINE_MILLIS,
      ),
    ).toBeNull();
    expect(
      isTransportRecoveryBudgetExhausted(
        MAX_TRANSPORT_RECOVERY_ATTEMPTS,
        TRANSPORT_RECOVERY_DEADLINE_MILLIS,
      ),
    ).toBe(true);

    const pending = applyRunRecoveryPending(
      {
        requestId: 'req-transport-unknown',
        loading: true,
        forceStop: false,
        multiAgent: { tasks: [] },
        tasks: [],
        agentRun: { status: 'RUNNING', todos: [] },
      } as unknown as CHAT.ChatItem,
      '服务端可能仍在运行',
      'TRANSPORT_RECOVERY_EXHAUSTED',
    );
    expect(pending.agentRun).toMatchObject({
      status: 'RUNNING',
      stopReason: 'TRANSPORT_RECOVERY_EXHAUSTED',
    });
    expect(canRegenerateChat(pending)).toBe(false);
  });

  it.each([
    new DOMException('aborted', 'AbortError'),
    new Error('401 Unauthorized'),
    new Error('quota exhausted'),
    new Error('Failed to parse SSE message'),
  ])('不可恢复的传输错误不应自动重试：%s', (error) => {
    expect(resolveTransportRecoveryDelay(error, 0)).toBeNull();
    expect(isFatalTransportRecoveryError(error)).toBe(true);
  });

  it('恢复预算是 run 全局预算，收到 heartbeat 或普通帧不会重置次数和时间', () => {
    let now = 10_000;
    const budget = createRecoveryBudgetTracker(() => now);

    budget.recordAttempt('transport');
    now += 3_000;
    parseAgentAnswer({
      status: 'success',
      packageType: 'heartbeat',
      finished: false,
      errorMsg: '',
    });

    expect(budget.completedAttempts('transport')).toBe(1);
    expect(budget.elapsedMillis('transport')).toBe(3_000);
    budget.recordAttempt('run');
    expect(budget.completedAttempts('transport')).toBe(1);
    expect(budget.completedAttempts('run')).toBe(1);
    expect(budget.elapsedMillis('run')).toBe(0);
  });

  it('所有自动 reconnect 必须复用同一个不可变 request body 与 requestId', () => {
    const request = createDurableRecoveryRequest({
      sessionId: 'session-1',
      requestId: 'req-durable-1',
      query: '继续当前任务',
    });
    const firstReconnectBody = request;
    const secondReconnectBody = request;

    expect(firstReconnectBody).toBe(secondReconnectBody);
    expect(secondReconnectBody.requestId).toBe('req-durable-1');
    expect(Object.isFrozen(secondReconnectBody)).toBe(true);
  });

  it('新 generation 会主动取消旧 transport，settle 后拒绝所有迟到事件', () => {
    const lifecycle = createStreamLifecycleController();
    let firstCancelled = 0;
    let secondCancelled = 0;
    let staleCancelled = 0;
    const firstGeneration = lifecycle.begin();

    expect(firstGeneration).not.toBeNull();
    lifecycle.attach(firstGeneration!, () => {
      firstCancelled += 1;
    });
    const secondGeneration = lifecycle.begin();

    expect(firstCancelled).toBe(1);
    expect(lifecycle.accepts(firstGeneration!)).toBe(false);
    expect(lifecycle.accepts(secondGeneration!)).toBe(true);
    lifecycle.attach(secondGeneration!, () => {
      secondCancelled += 1;
    });
    expect(lifecycle.settle()).toBe(true);
    expect(secondCancelled).toBe(1);
    expect(lifecycle.accepts(secondGeneration!)).toBe(false);
    expect(lifecycle.settle()).toBe(false);
    expect(
      lifecycle.attach(secondGeneration!, () => {
        staleCancelled += 1;
      }),
    ).toBe(false);
    expect(staleCancelled).toBe(1);
  });

  it('会话切换或 abort 清理恢复 timer 后不得再次发起连接', () => {
    vi.useFakeTimers();
    try {
      const timer = createRecoveryTimerController();
      const reconnect = vi.fn();

      expect(timer.schedule(1_000, reconnect)).toBe(true);
      expect(timer.hasPending()).toBe(true);
      timer.clear();
      vi.advanceTimersByTime(2_000);

      expect(timer.hasPending()).toBe(false);
      expect(reconnect).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('replay 事件同时按 messageId 与 messageOrder 去重', () => {
    const deduper = createReplayEventDeduper();
    const base = {
      messageOrder: 7,
      messageType: 'agent_event',
      messageId: 'message-7',
      taskId: 'run-1',
      taskOrder: 1,
      resultMap: { messageType: 'tool_result' },
    } as unknown as MESSAGE.EventData;

    expect(deduper.accept(base)).toBe(true);
    expect(deduper.accept({ ...base, messageOrder: 8 })).toBe(false);
    expect(deduper.accept({ ...base, messageId: 'message-8' })).toBe(false);
    expect(
      deduper.accept({ ...base, messageId: 'message-8', messageOrder: 8 }),
    ).toBe(true);
    expect(
      deduper.accept({
        ...base,
        messageId: 'message-9',
        resultMap: { messageType: 'phase_changed' },
      } as MESSAGE.EventData),
    ).toBe(true);
  });

  it('live 随机 ID 切到 FINISHED 稳定 replay ID 时先清空旧投影再完整重放', () => {
    const deduper = createReplayEventDeduper();
    const liveEvent = {
      messageOrder: 193_001,
      messageType: 'agent_event',
      messageId: 'live-random-uuid',
      taskId: 'req-replay-1',
      taskOrder: 1,
      resultMap: { messageType: 'agent_stream', result: '部分答案' },
    } as unknown as MESSAGE.EventData;
    const replayEvent = {
      ...liveEvent,
      messageOrder: 1,
      messageId: 'req-replay-1:llm:17',
    } as MESSAGE.EventData;
    const liveChat = {
      requestId: 'req-replay-1',
      loading: true,
      forceStop: false,
      response: '部分答案',
      tasks: [[{ id: 'live-task' }]],
      multiAgent: { tasks: [[{ id: 'live-task' }]] },
      timeline: [{ seq: 1, type: 'agent_stream', area: 'answer', title: '部分答案' }],
      conclusion: { id: 'live-conclusion', result: '部分答案' },
      agentRun: { status: 'RUNNING', todos: [{ id: 'todo-live', title: '搜索', status: 'in_progress' }] },
      metrics: { status: 'RUNNING', totalTokens: 20 },
    } as unknown as CHAT.ChatItem;

    expect(deduper.accept(liveEvent)).toBe(true);
    const reset = resetChatForAuthoritativeReplay(liveChat);
    deduper.reset();

    expect(reset.response).toBe('');
    expect(reset.tasks).toEqual([]);
    expect(reset.multiAgent.tasks).toEqual([]);
    expect(reset.timeline).toEqual([]);
    expect(reset.conclusion).toBeUndefined();
    expect(reset.agentRun).toMatchObject({ status: 'RUNNING', todos: [] });
    expect(deduper.accept(replayEvent)).toBe(true);
    expect(shouldResetForAuthoritativeReplay(true, replayEvent, null)).toBe(true);
    expect(
      shouldResetForAuthoritativeReplay(true, replayEvent, {
        retry: true,
        exhausted: false,
        retryable: true,
        delayMillis: 1_000,
      }),
    ).toBe(false);
  });

  it('heartbeat 包在缺少 resultMap 时也应被正常解析', () => {
    const result = parseAgentAnswer({
      status: 'success',
      packageType: 'heartbeat',
      finished: false,
      response: '',
      responseAll: '',
      useTimes: 0,
      useTokens: 0,
      responseType: 'text',
      encrypted: false,
      errorMsg: '',
    });

    expect(result.packageType).toBe('heartbeat');
    expect(result.resultMap).toEqual({});
  });

  it('result 包的 errorMsg 为 null 时也应被正常解析', () => {
    const result = parseAgentAnswer({
      status: 'running',
      packageType: 'result',
      finished: false,
      response: '',
      responseAll: '',
      useTimes: 0,
      useTokens: 0,
      responseType: 'text',
      encrypted: false,
      errorMsg: null,
      resultMap: {
        eventData: {
          messageOrder: 1,
          messageType: 'agent_event',
          messageId: 'msg-1',
          taskId: 'task-1',
          taskOrder: 1,
          resultMap: {
            messageType: 'agent_stream',
            result: '正在处理',
          },
        },
      },
    });

    expect(result.errorMsg).toBe('');
    expect(result.resultMap.eventData).toBeDefined();
  });

  it('实时 AgentResponse 帧应转换为 canonical agent_event envelope', () => {
    const result = parseAgentAnswer({
      requestId: 'req-1',
      messageId: 'msg-1',
      messageType: 'agent_stream',
      messageTime: '1783418076172',
      messageOrder: 42,
      taskOrder: 6,
      result: '你好',
      finish: false,
      isFinal: true,
      resultMap: {},
    });

    expect(result.status).toBe('running');
    expect(result.packageType).toBe('result');
    expect(result.finished).toBe(false);
    expect(result.resultMap.eventData?.messageType).toBe('agent_event');
    expect(result.resultMap.eventData?.messageOrder).toBe(42);
    expect(result.resultMap.eventData?.taskOrder).toBe(6);
    expect(result.resultMap.eventData?.resultMap.messageType).toBe('agent_stream');
    expect(result.resultMap.eventData?.resultMap.result).toBe('你好');
  });

  it('缺少显式 order 的 direct frame 应按 messageId 生成稳定且非固定的顺序号', () => {
    const first = parseAgentAnswer({
      requestId: 'req-order-1',
      messageId: 'message-order-a',
      messageType: 'agent_stream',
      messageTime: '1783418076172',
      result: 'A',
      finish: false,
      isFinal: false,
      resultMap: {},
    });
    const second = parseAgentAnswer({
      requestId: 'req-order-1',
      messageId: 'message-order-b',
      messageType: 'agent_stream',
      messageTime: '1783418076172',
      result: 'B',
      finish: false,
      isFinal: false,
      resultMap: {},
    });

    expect(first.resultMap.eventData?.messageOrder).toBeGreaterThan(1);
    expect(second.resultMap.eventData?.messageOrder).not.toBe(
      first.resultMap.eventData?.messageOrder,
    );
  });

  it('canonical RUNNING envelope 应原样保留，不得被 finish 默认值升级为成功', () => {
    const result = parseAgentAnswer({
      status: 'RUNNING',
      packageType: 'result',
      finished: false,
      errorMsg: '',
      resultMap: {
        eventData: {
          messageOrder: 9,
          messageType: 'agent_event',
          messageId: 'run-started-9',
          taskId: 'req-running-9',
          taskOrder: 1,
          resultMap: {
            messageType: 'run_started',
            runStatus: 'RUNNING',
          },
        },
      },
    });

    expect(result.status).toBe('RUNNING');
    expect(result.finished).toBe(false);
    expect(
      (result.resultMap.eventData?.resultMap as unknown as Record<string, unknown>)?.runStatus,
    ).toBe('RUNNING');
  });

  it('直接 AgentResponse 的失败终态不能在转换时被重写成 success', () => {
    const result = parseAgentAnswer({
      requestId: 'req-failed-1',
      messageId: 'msg-failed-1',
      messageType: 'result',
      messageTime: '1783418076172',
      result: '',
      finish: true,
      isFinal: true,
      status: 'FAILED',
      errorCode: 'COMPLETION_GATE_FAILED',
      errorMessage: '完成门禁未通过。',
      resultMap: {
        runStatus: 'FAILED',
        taskSummary: '完成门禁未通过。',
      },
    });

    expect(result.status).toBe('FAILED');
    expect(result.errorMsg).toContain('完成门禁未通过');
    expect(result.resultMap.eventData?.resultMap).toMatchObject({
      runStatus: 'FAILED',
      status: 'FAILED',
      errorCode: 'COMPLETION_GATE_FAILED',
    });
  });

  it('直接 RunClaim AgentResponse 应保留 durable recovery 字段', () => {
    const result = parseAgentAnswer({
      requestId: 'req-running-1',
      messageId: 'run-finished-running-1',
      messageType: 'run_finished',
      messageTime: '1783418076172',
      finish: false,
      isFinal: true,
      status: 'STOPPED',
      resultMap: {
        runStatus: 'STOPPED',
        stopReason: 'RUN_ALREADY_IN_PROGRESS',
        retryable: true,
        retryAfterMillis: 1_000,
        existingRunId: 'req-running-1',
      },
    });

    expect(result.resultMap.eventData?.resultMap).toMatchObject({
      runStatus: 'STOPPED',
      stopReason: 'RUN_ALREADY_IN_PROGRESS',
      retryable: true,
      retryAfterMillis: 1_000,
      existingRunId: 'req-running-1',
    });
  });

  it('canonical todo_snapshot AgentResponse 帧应保留结构化载荷', () => {
    const result = parseAgentAnswer({
      requestId: 'req-1',
      messageId: 'todo-message-1',
      messageType: 'todo_snapshot',
      messageTime: '1783418076172',
      finish: false,
      isFinal: true,
      resultMap: {
        title: '调研计划',
        todos: [{ id: 'todo-1', title: '搜索资料', status: 'in_progress' }],
      },
    });

    expect(result.resultMap.eventData?.resultMap.messageType).toBe('todo_snapshot');
    expect(result.resultMap.eventData?.resultMap.resultMap).toMatchObject({
      title: '调研计划',
      todos: [{ id: 'todo-1', status: 'in_progress' }],
    });
  });
});
