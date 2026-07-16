import type { AccountSummary } from '@/services/bff';

export type PaymentOutcome = 'paid-waiting-group' | 'quota-granted';

export function resolvePaymentOutcome(summary: AccountSummary): PaymentOutcome {
  if ((summary.pendingGroupOrders?.length ?? 0) > 0) {
    return 'paid-waiting-group';
  }
  return 'quota-granted';
}

export function paymentOutcomeMessage(outcome: PaymentOutcome): string {
  if (outcome === 'quota-granted') {
    return '支付成功，额度已发放，可立即使用。';
  }
  return '支付成功，订单正在等待成团；成团后将自动发放基础额度和赠送额度。';
}
