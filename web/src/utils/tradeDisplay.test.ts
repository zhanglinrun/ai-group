import { describe, expect, it } from 'vitest';

import { quotaLedgerAmountView } from './tradeDisplay';

describe('quotaLedgerAmountView', () => {
  it('does not present a quota reservation as a balance grant', () => {
    expect(quotaLedgerAmountView('FREEZE', 507_545)).toEqual({
      text: '预留 0.507545 点',
      tone: 'pending',
    });
  });

  it('presents the zero-amount release ledger as a released reservation', () => {
    expect(quotaLedgerAmountView('RELEASE', 0)).toEqual({
      text: '预留已释放',
      tone: 'neutral',
    });
  });

  it('keeps real grants and settlements signed', () => {
    expect(quotaLedgerAmountView('GRANT', 60_000_000)).toEqual({
      text: '+60 点',
      tone: 'positive',
    });
    expect(quotaLedgerAmountView('CONFIRM', -16_025)).toEqual({
      text: '-0.016025 点',
      tone: 'negative',
    });
  });
});
