import { describe, expect, it } from 'vitest';

import { applyAgentEvent, normalizeAgentEvent } from './agentEvents';
import { parseAgentAnswer } from './sseParsers';

const event = (messageType: string, resultMap: Record<string, unknown>) =>
  ({
    messageOrder: 1,
    messageType: 'agent_event',
    messageId: 'message-1',
    taskId: 'task-1',
    taskOrder: 1,
    resultMap: { messageType, resultMap },
  }) as unknown as MESSAGE.EventData;

describe('agentEvents', () => {
  it('只接受 canonical todo_snapshot，不再把旧 plan 映射为 todo', () => {
    expect(
      normalizeAgentEvent(
        event('todo_snapshot', {
          title: '竞品调研',
          todos: [
            {
              id: 'todo-1',
              title: '搜索官方资料',
              status: 'in_progress',
              evidencePolicy: 'TOOL',
            },
          ],
        }),
      ),
    ).toMatchObject({
      type: 'todo_snapshot',
      title: '竞品调研',
      todos: [{ id: 'todo-1', status: 'in_progress', evidencePolicy: 'TOOL' }],
    });

    expect(normalizeAgentEvent(event('plan', { steps: ['旧计划'] }))).toBeUndefined();
  });

  it('只接受 verification_result，不再兼容 evaluation', () => {
    expect(
      normalizeAgentEvent(
        event('verification_result', {
          accepted: false,
          failureReasons: ['Cursor 价格缺失'],
          requiredActions: ['补充官方来源'],
        }),
      ),
    ).toMatchObject({
      type: 'verification_result',
      verification: {
        status: 'failed',
        missingRequirements: ['Cursor 价格缺失'],
        requiredActions: ['补充官方来源'],
      },
    });

    expect(normalizeAgentEvent(event('evaluation', { status: 'failed' }))).toBeUndefined();
  });

  it('将 completion_blocked 保留为可见的失败验收状态', () => {
    const normalized = normalizeAgentEvent(
      event('completion_blocked', {
        attempt: 2,
        reasons: ['搜索工具仍失败'],
        requiredActions: ['联网恢复后重试搜索'],
      }),
    );

    expect(normalized).toEqual({
      type: 'completion_blocked',
      verification: {
        status: 'failed',
        missingRequirements: ['搜索工具仍失败'],
        requiredActions: ['联网恢复后重试搜索'],
        attempt: 2,
      },
    });

    const chat = { multiAgent: { tasks: [] } } as unknown as CHAT.ChatItem;
    if (normalized) applyAgentEvent(chat, normalized);
    expect(chat.agentRun).toMatchObject({
      phase: 'VERIFYING',
      verification: { status: 'failed', attempt: 2 },
    });
  });

  it('将 todo snapshot、verification 与 run_finished 写入统一运行状态', () => {
    const chat = { multiAgent: { tasks: [] } } as unknown as CHAT.ChatItem;
    applyAgentEvent(chat, {
      type: 'todo_snapshot',
      title: '调研计划',
      todos: [{ id: 'todo-1', title: '搜索资料', status: 'in_progress' }],
    });
    applyAgentEvent(chat, {
      type: 'verification_result',
      verification: { status: 'passed', summary: '要求已满足' },
    });
    applyAgentEvent(chat, {
      type: 'run_finished',
      status: 'SUCCESS',
      completionGatePassed: true,
      stopReason: 'COMPLETED',
    });
    expect(chat.agentRun).toMatchObject({
      status: 'SUCCESS',
      phase: 'FINALIZING',
      terminalEventSeen: true,
      completionGatePassed: true,
      stopReason: 'COMPLETED',
      todos: [{ id: 'todo-1', status: 'in_progress' }],
      verification: { status: 'passed' },
    });
  });

  it('SUCCESS run_finished 缺少 completion gate 时必须降级为 FAILED', () => {
    const chat = { multiAgent: { tasks: [] } } as unknown as CHAT.ChatItem;
    applyAgentEvent(chat, {
      type: 'run_finished',
      status: 'SUCCESS',
      completionGatePassed: false,
    });
    expect(chat.agentRun).toMatchObject({
      status: 'FAILED',
      terminalEventSeen: true,
      completionGatePassed: false,
    });
  });

  it('仅有成功 run_finished 时也恢复最小验证通过状态', () => {
    const chat = { multiAgent: { tasks: [] } } as unknown as CHAT.ChatItem;
    applyAgentEvent(chat, {
      type: 'run_finished',
      status: 'SUCCESS',
      completionGatePassed: true,
      stopReason: 'COMPLETED',
    });

    expect(chat.agentRun).toMatchObject({
      status: 'SUCCESS',
      verification: { status: 'passed' },
    });
  });

  it('不再接受无生产者的 todo_updated', () => {
    expect(
      normalizeAgentEvent(
        event('todo_updated', {
          todo: { id: 'todo-1', title: '旧增量事件', status: 'completed' },
        }),
      ),
    ).toBeUndefined();
  });

  it('拒绝非 canonical 阶段和运行状态', () => {
    expect(normalizeAgentEvent(event('phase_changed', { phase: 'PAUSED' }))).toBeUndefined();
    expect(normalizeAgentEvent(event('run_finished', { status: 'PENDING' }))).toBeUndefined();
  });

  it('可直接消费后端 AgentResponse canonical 事件', () => {
    const answer = parseAgentAnswer({
      requestId: 'req-1',
      messageId: 'run-finished-1',
      messageType: 'run_finished',
      finish: false,
      isFinal: true,
      resultMap: {
        status: 'SUCCESS',
        completionGatePassed: true,
        stopReason: 'COMPLETED',
      },
    });

    expect(normalizeAgentEvent(answer.resultMap.eventData!)).toEqual({
      type: 'run_finished',
      status: 'SUCCESS',
      completionGatePassed: true,
      stopReason: 'COMPLETED',
    });
  });
});
