import { describe, expect, it } from 'vitest';
import { summarizeTradeWorkspace } from './tradeWorkspace';

describe('summarizeTradeWorkspace', () => {
  it('does not count granted or formed orders as waiting for a group', () => {
    const summary = summarizeTradeWorkspace(null, [
      { orderId: 'waiting', marketType: 1, displayStatus: 'PAID_WAIT_GROUP' },
      { orderId: 'formed', marketType: 1, displayStatus: 'GROUP_FORMED' },
      { orderId: 'granted', marketType: 1, displayStatus: 'BENEFIT_GRANTED' },
    ]);

    expect(summary.groupOrders).toBe(3);
    expect(summary.waitingGroupOrders).toBe(1);
  });
});
