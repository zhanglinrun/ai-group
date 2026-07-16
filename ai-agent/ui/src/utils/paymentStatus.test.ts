import { describe, expect, it } from 'vitest';

import { paymentOutcomeMessage, resolvePaymentOutcome } from './paymentStatus';

describe('paymentStatus', () => {
  it('没有待成团订单时返回 quota-granted', () => {
    expect(resolvePaymentOutcome({ paidQuotaBalance: 60_000_000 })).toBe('quota-granted');
  });

  it('存在待成团订单时返回 paid-waiting-group', () => {
    expect(
      resolvePaymentOutcome({
        pendingGroupOrders: [{ orderId: 'o-1' }],
      }),
    ).toBe('paid-waiting-group');
  });

  it('文案区分等待成团与额度到账', () => {
    expect(paymentOutcomeMessage('paid-waiting-group')).toContain('等待成团');
    expect(paymentOutcomeMessage('quota-granted')).toContain('额度已发放');
  });
});
