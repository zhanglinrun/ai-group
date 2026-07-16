import { describe, expect, it } from 'vitest';

import {
  applyCheckpointControlEvent,
  parseCheckpointControlEvent,
  requiresExplicitRestartConfirmation,
} from './checkpoint';

const eventData = (messageType: string, payload: Record<string, unknown>) =>
  ({
    messageOrder: 1,
    messageType: 'task',
    messageId: 'message-1',
    taskId: 'request-1',
    taskOrder: 1,
    resultMap: {
      id: 'message-1',
      requestId: 'request-1',
      messageId: 'message-1',
      messageTime: '1',
      messageType,
      finish: false,
      isFinal: true,
      resultMap: payload,
    },
  }) as MESSAGE.EventData;

describe('checkpoint control events', () => {
  it('解析真实 checkpoint SSE 载荷并写入当前 chat', () => {
    const controlEvent = parseCheckpointControlEvent(
      eventData('checkpoint', {
        checkpointId: 'checkpoint-001',
        phase: 'READY_FOR_STEP',
        sequence: 4,
        nextStepIndex: 2,
        resumable: true,
      }),
    );

    expect(controlEvent).toEqual({
      kind: 'checkpoint',
      checkpoint: {
        checkpointId: 'checkpoint-001',
        phase: 'READY_FOR_STEP',
        sequence: 4,
        nextStepIndex: 2,
        resumable: true,
        sourceRequestId: undefined,
        resumeDecision: undefined,
        status: 'AVAILABLE',
      },
    });

    const chat = applyCheckpointControlEvent(
      { requestId: 'request-1' } as CHAT.ChatItem,
      controlEvent!,
    );
    expect(chat.checkpoint?.checkpointId).toBe('checkpoint-001');
  });

  it('resume 事件会标记恢复方式且不再提供重复恢复', () => {
    const controlEvent = parseCheckpointControlEvent(
      eventData('resume', {
        checkpointId: 'checkpoint-002',
        sourceRequestId: 'request-source',
        phase: 'BEFORE_SUMMARY',
        resumeDecision: 'SAFE_ONLY',
      }),
    );

    expect(controlEvent?.checkpoint).toMatchObject({
      checkpointId: 'checkpoint-002',
      sourceRequestId: 'request-source',
      phase: 'BEFORE_SUMMARY',
      resumeDecision: 'SAFE_ONLY',
      resumable: false,
      status: 'RESUMED',
    });
  });

  it('RESTART_FROM_CHECKPOINT 必须显式确认', () => {
    expect(requiresExplicitRestartConfirmation('SAFE_ONLY')).toBe(false);
    expect(requiresExplicitRestartConfirmation('RESTART_FROM_CHECKPOINT')).toBe(true);
  });
});
