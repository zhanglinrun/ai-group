import type { AccountSummary } from '@/services/bff';

export type PaymentOutcome = 'paid-waiting-group' | 'pro-active';

export function resolvePaymentOutcome(summary: AccountSummary): PaymentOutcome {
  const tier = (summary.tier || '').toUpperCase();
  const hasActivePro =
    tier === 'PRO' &&
    Boolean(summary.expireAt) &&
    new Date(summary.expireAt as string).getTime() > Date.now();

  if (hasActivePro) {
    return 'pro-active';
  }

  return 'paid-waiting-group';
}

export function paymentOutcomeMessage(outcome: PaymentOutcome): string {
  if (outcome === 'pro-active') {
    return 'Pro 会员已生效，可立即使用全部权益。';
  }
  return '支付成功！订单已提交，正在等待拼团成团，成团后 Pro 会员将自动开通。';
}
