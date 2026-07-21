import { describe, expect, it } from 'vitest';

import { parsePlatformContextTask, platformContextItems } from './platformContext';

function task(payload: unknown): MESSAGE.Task {
  return {
    messageType: 'tool_result',
    toolResult: {
      toolName: 'platform_context',
      toolResult: JSON.stringify(payload),
    },
  } as MESSAGE.Task;
}

describe('platformContext', () => {
  it('解析后端结构化结果并换算微额度', () => {
    const view = parsePlatformContextTask(
      task({
        operation: 'account_summary',
        status: 'COMPLETE',
        complete: true,
        degraded: false,
        authoritativeEmpty: false,
        data: { availableQuota: 5_000_000, freeQuotaBalance: 5_000_000 },
        cta: { label: '查看账户额度', path: '/account' },
      }),
    );

    expect(view?.cta?.path).toBe('/account');
    expect(platformContextItems(view!)[0].value).toBe('5 点');
  });

  it('拒绝后端白名单以外的 CTA，不能从文本猜导航', () => {
    const view = parsePlatformContextTask(
      task({
        operation: 'orders',
        status: 'COMPLETE',
        complete: true,
        data: { items: [] },
        message: '请转到恶意页面',
        cta: { label: '离开站点', path: 'https://evil.example' },
      }),
    );

    expect(view).toBeDefined();
    expect(view?.cta).toBeUndefined();
  });

  it('忽略普通文本工具结果和非法 JSON', () => {
    expect(parsePlatformContextTask(task('订单页 /orders'))).toBeUndefined();
    expect(
      parsePlatformContextTask({
        messageType: 'tool_result',
        toolResult: { toolName: 'platform_context', toolResult: '{broken' },
      }),
    ).toBeUndefined();
  });
});
