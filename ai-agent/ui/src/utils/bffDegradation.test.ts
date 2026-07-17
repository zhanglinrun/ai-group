import { describe, expect, it } from 'vitest';

import { bffDegradationMessage } from './bffDegradation';

describe('bffDegradationMessage', () => {
  it('stays empty for healthy or absent metadata', () => {
    expect(bffDegradationMessage(undefined, { degraded: false })).toBe('');
  });

  it('deduplicates degraded services and uses user-facing labels', () => {
    expect(
      bffDegradationMessage(
        { degraded: true, errors: [{ service: 'group' }, { service: 'group' }] },
        { degraded: true, errors: [{ service: 'pay' }] },
      ),
    ).toBe('部分数据暂不可用（拼团服务、订单支付服务），当前页面可能不完整，请稍后刷新。');
  });

  it('keeps a generic warning when a degraded response omits error details', () => {
    expect(bffDegradationMessage({ degraded: true })).toBe(
      '部分数据暂不可用，当前页面可能不完整，请稍后刷新。',
    );
  });
});
