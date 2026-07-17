import { describe, expect, it } from 'vitest';
import type {
  ConversationHistoryDetail,
  ConversationReplayFrame,
} from '@/services/agentConversation';

import {
  hydrateConversationFromReplayFrames,
  isHistoryDetailEmpty,
  toConversationHistoryTitle,
} from './conversationHistory';
import { buildConversationTaskData } from './chat';
import { getTaskFiles } from './taskArtifacts';

function createReplayFrame(eventData: MESSAGE.EventData): ConversationReplayFrame {
  return {
    reqId: 'req-history-001',
    status: 'success',
    finished: true,
    resultMap: { multiAgent: {}, eventData },
  };
}

function createEvent(
  messageType: string,
  payload: Record<string, unknown>,
  options?: { messageId?: string; taskId?: string; finish?: boolean },
): MESSAGE.EventData {
  const messageId = options?.messageId || `msg-${messageType}`;
  return {
    taskId: options?.taskId || 'task-1',
    taskOrder: 1,
    messageType: 'agent_event',
    messageOrder: 1,
    messageId,
    resultMap: {
      requestId: 'req-history-001',
      messageId,
      messageType,
      messageTime: '1714620000000',
      isFinal: options?.finish ?? true,
      finish: options?.finish ?? false,
      resultMap: payload,
      ...payload,
    } as unknown as MESSAGE.Task,
  };
}

function createDetail(
  overrides: Partial<ConversationHistoryDetail> = {},
): ConversationHistoryDetail {
  return {
    sessionId: 'session-history-001',
    title: '项目风险分析',
    status: 'SUCCESS',
    outputStyle: 'chat',
    executionMode: 'STANDARD',
    role: null,
    runCount: 1,
    finishedRunCount: 1,
    failedRunCount: 0,
    startedAt: '2026-05-02T10:00:00',
    lastActiveAt: '2026-05-02T10:01:00',
    runs: [],
    ...overrides,
  };
}

describe('conversationHistory hydrate', () => {
  it('restores model, token, duration and execution mode from history runs', () => {
    const history = hydrateConversationFromReplayFrames(
      createDetail({
        executionMode: 'DEEP',
        runs: [
          {
            requestId: 'req-metrics-001',
            status: 'SUCCESS',
            queryText: '展示运行指标',
            modelName: 'qwen-max',
            totalTokens: 128,
            durationMs: 950,
            replayFrames: [],
          },
        ],
      }),
    );

    expect(history.executionMode).toBe('DEEP');
    expect(history.chatList[0].metrics).toEqual({
      status: 'SUCCESS',
      modelName: 'qwen-max',
      totalTokens: 128,
      durationMs: 950,
    });
    expect(history.chatList[0].agentRun).toMatchObject({
      status: 'SUCCESS',
      terminalEventSeen: true,
      completionGatePassed: true,
      verification: { status: 'passed' },
    });
  });

  it('replays canonical todo state and ordinary tool/result history without AgentType', () => {
    const history = hydrateConversationFromReplayFrames(
      createDetail({
        runs: [
          {
            requestId: 'req-history-001',
            status: 'SUCCESS',
            queryText: '先分析项目风险',
            finalSummaryText: '建议先收敛风险清单',
            replayFrames: [
              createReplayFrame(
                createEvent('todo_snapshot', {
                  title: '风险分析',
                  todos: [
                    { id: 'todo-1', title: '读取资料', status: 'completed' },
                    { id: 'todo-2', title: '整理结论', status: 'in_progress' },
                  ],
                }),
              ),
              createReplayFrame(
                createEvent('tool_result', {
                  toolResult: {
                    toolName: 'read_tool',
                    toolResult: '已整理现有资料',
                  },
                }),
              ),
              createReplayFrame(
                createEvent(
                  'result',
                  { result: '建议先收敛风险清单', taskSummary: '建议先收敛风险清单' },
                  { finish: true },
                ),
              ),
            ],
          },
        ],
      }),
    );

    const chat = history.chatList[0];
    expect(chat.agentRun).toMatchObject({
      todoTitle: '风险分析',
      todos: [
        { id: 'todo-1', status: 'completed' },
        { id: 'todo-2', status: 'in_progress' },
      ],
    });
    expect(chat.conclusion?.result).toBe('建议先收敛风险清单');
    expect(buildConversationTaskData(chat).taskList.map((task) => task.messageType)).toContain(
      'tool_result',
    );
  });

  it('keeps empty history as blank state input', () => {
    const detail = createDetail({
      title: '新对话',
      status: 'RUNNING',
      runCount: 0,
      finishedRunCount: 0,
      runs: [],
    });

    expect(isHistoryDetailEmpty(detail)).toBe(true);
    const history = hydrateConversationFromReplayFrames(detail);
    expect(history.chatList).toHaveLength(0);
    expect(history.dataChatList).toHaveLength(0);
    expect(toConversationHistoryTitle(detail)).toBe('新对话');
  });

  it('marks stopped history run as force stop and preserves missing artifact state', () => {
    const history = hydrateConversationFromReplayFrames(
      createDetail({
        runs: [
          {
            requestId: 'req-stopped-001',
            status: 'STOPPED',
            queryText: '生成报告',
            replayFrames: [
              createReplayFrame({
                ...createEvent('result', { result: '任务已停止', taskSummary: '任务已停止' }),
                artifactRefs: [
                  {
                    displayName: '未完成报告.md',
                    resourceKey: 'missing-report',
                    missing: true,
                    missingReason: 'artifact_not_found',
                  },
                ],
              }),
            ],
          },
        ],
      }),
    );

    const chat = history.chatList[0];
    expect(chat.forceStop).toBe(true);
    expect(chat.agentRun).toMatchObject({
      status: 'STOPPED',
      terminalEventSeen: true,
      completionGatePassed: false,
    });
    expect(getTaskFiles(chat.conclusion)[0]).toMatchObject({
      name: '未完成报告.md',
      missing: true,
    });
  });

  it('keeps code interpreter history task when replay only contains codeOutput', () => {
    const history = hydrateConversationFromReplayFrames(
      createDetail({
        runs: [
          {
            requestId: 'req-code-001',
            status: 'SUCCESS',
            queryText: '运行代码',
            replayFrames: [
              createReplayFrame(
                createEvent('code', { codeOutput: '42', isFinal: true }, { messageId: 'code-1' }),
              ),
            ],
          },
        ],
      }),
    );

    expect(buildConversationTaskData(history.chatList[0]).taskList[0]).toMatchObject({
      messageType: 'code',
      resultMap: { codeOutput: '42' },
    });
  });

  it('parses $$$ summary fallback into summary text and attachments', () => {
    const history = hydrateConversationFromReplayFrames(
      createDetail({
        runs: [
          {
            requestId: 'req-fallback-001',
            status: 'SUCCESS',
            queryText: '生成报告',
            finalSummaryText: '报告已生成$$$tool-1::竞品报告.md',
            replayFrames: [],
          },
        ],
      }),
    );

    const chat = history.chatList[0];
    expect(chat.conclusion?.result).toBe('报告已生成');
    expect(getTaskFiles(chat.conclusion)[0]).toMatchObject({
      name: '竞品报告.md',
      missing: true,
    });
  });
});
