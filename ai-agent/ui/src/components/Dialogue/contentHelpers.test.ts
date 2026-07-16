import { describe, expect, it } from 'vitest';

import { resolveConversationConclusionText } from './contentHelpers';

const emptyConclusion = {
  id: 'empty-result',
  messageId: 'empty-result',
  requestId: 'req-1',
  messageTime: '1',
  messageType: 'result',
  finish: true,
  isFinal: true,
  result: '',
  resultMap: {},
} as CHAT.Task;

describe('contentHelpers', () => {
  it('空失败结果不能兜底为任务已完成', () => {
    const text = resolveConversationConclusionText({
      conclusion: emptyConclusion,
      tip: '质量评估未通过，已达到最大重规划轮次。',
      metrics: { status: 'FAILED' },
    } as CHAT.ChatItem);

    expect(text).toBe('质量评估未通过，已达到最大重规划轮次。');
    expect(text).not.toBe('任务已完成');
  });

  it('空停止结果应展示停止终态', () => {
    expect(
      resolveConversationConclusionText({
        conclusion: emptyConclusion,
        metrics: { status: 'STOPPED' },
      } as CHAT.ChatItem),
    ).toContain('任务已停止');
  });

  it('成功结果仍保留原有兜底', () => {
    expect(
      resolveConversationConclusionText({
        conclusion: emptyConclusion,
        metrics: { status: 'SUCCESS' },
      } as CHAT.ChatItem),
    ).toBe('任务已完成');
  });
});
