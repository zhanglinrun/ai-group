import { describe, expect, it } from 'vitest';

import {
  applyGuardError,
  applyTerminalRunError,
  resolveTerminalRunState,
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
    const state = resolveTerminalRunState(
      { status: 'success', finished: true, errorMsg: '' },
      {
        resultMap: {
          messageType: 'result',
          finish: true,
          resultMap: {
            runStatus: 'FAILED',
            errorMsg: '质量评估未通过',
          },
        },
      } as unknown as MESSAGE.EventData,
    );

    expect(state).toEqual({ status: 'FAILED', message: '质量评估未通过' });
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

  it('存在 plan 但没有 renderable task 时仍应打开右侧工作区', () => {
    expect(
      resolveActionPanelVisibility({
        plan: {
          stages: [{ title: '分析需求', status: 'completed' }],
        } as unknown as CHAT.Plan,
        taskList: [],
      }),
    ).toBe(true);
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
          messageType: 'task',
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

  it('实时 AgentResponse 帧应兼容转换为前端 envelope', () => {
    const result = parseAgentAnswer({
      requestId: 'req-1',
      messageId: 'msg-1',
      messageType: 'agent_stream',
      messageTime: '1783418076172',
      result: '你好',
      finish: false,
      isFinal: true,
      resultMap: {
        agentType: 2,
      },
    });

    expect(result.status).toBe('running');
    expect(result.packageType).toBe('result');
    expect(result.finished).toBe(false);
    expect(result.resultMap.eventData?.messageType).toBe('task');
    expect(result.resultMap.eventData?.resultMap.messageType).toBe('agent_stream');
    expect(result.resultMap.eventData?.resultMap.result).toBe('你好');
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
      errorCode: 'PLAN_EVALUATION_REPLAN_EXHAUSTED',
      errorMessage: '质量评估未通过，已达到最大定向重规划轮次。',
      resultMap: {
        runStatus: 'FAILED',
        taskSummary: '质量评估未通过，已达到最大定向重规划轮次。',
      },
    });

    expect(result.status).toBe('FAILED');
    expect(result.errorMsg).toContain('质量评估未通过');
    expect(result.resultMap.eventData?.resultMap).toMatchObject({
      runStatus: 'FAILED',
      status: 'FAILED',
      errorCode: 'PLAN_EVALUATION_REPLAN_EXHAUSTED',
    });
  });

  it('checkpoint AgentResponse 帧应保留后端控制字段', () => {
    const result = parseAgentAnswer({
      requestId: 'req-1',
      messageId: 'checkpoint-message-1',
      messageType: 'checkpoint',
      messageTime: '1783418076172',
      finish: false,
      isFinal: true,
      resultMap: {
        agentType: 3,
        checkpointId: 'checkpoint-001',
        phase: 'READY_FOR_STEP',
        sequence: 2,
        nextStepIndex: 1,
        resumable: true,
      },
    });

    expect(result.resultMap.eventData?.resultMap.messageType).toBe('checkpoint');
    expect(result.resultMap.eventData?.resultMap.resultMap).toMatchObject({
      checkpointId: 'checkpoint-001',
      phase: 'READY_FOR_STEP',
      resumable: true,
    });
  });
});
