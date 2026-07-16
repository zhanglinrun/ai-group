import type { AccountSummary, OrderItem } from '@/services/bff';

export type TradeSettlementTone = 'neutral' | 'warn' | 'ready' | 'danger';

export type TradeSettlementHint = {
  label: string;
  detail: string;
  tone: TradeSettlementTone;
};

export type TradeWorkspaceSummary = {
  availableQuota: number;
  freeQuota: number;
  paidQuota: number;
  frozenQuota: number;
  totalOrders: number;
  groupOrders: number;
  waitingGroupOrders: number;
  recentOrders: OrderItem[];
  consistencyHints: string[];
};

const WAITING_STATUSES = new Set(['pay_success', 'PAY_SUCCESS', 'WAIT_GROUP', 'waiting']);

const DONE_STATUSES = new Set(['deal_done', 'formed', 'DEAL_DONE', 'GROUP_SETTLED']);

const DISPLAY_STATUS_LABELS: Record<string, string> = {
  PAY_WAIT: '待支付',
  PAID_WAIT_GROUP: '支付成功，待成团',
  GROUP_FORMED: '已成团，权益发放中',
  BENEFIT_GRANTED: '额度已到账',
  WAIT_REFUND: '退款中',
  CLOSED: '已关闭',
  PAID: '已支付',
};

export function tradeOrderStatusLabel(
  status?: string,
  groupStatus?: string,
  displayStatus?: string,
): string {
  if (displayStatus && DISPLAY_STATUS_LABELS[displayStatus]) {
    return DISPLAY_STATUS_LABELS[displayStatus];
  }
  const normalized = (status || '').toLowerCase();
  const group = (groupStatus || '').toLowerCase();
  if (group === 'waiting' || WAITING_STATUSES.has(status || '')) {
    return '等待成团';
  }
  if (group === 'formed' || DONE_STATUSES.has(status || '')) {
    return '已成团';
  }
  const labels: Record<string, string> = {
    create: '已创建',
    pay_wait: '待支付',
    pay_success: '已支付',
    deal_done: '已完成',
    close: '已关闭',
  };
  return labels[normalized] || status || '未知';
}

export function tradeSettlementHint(order: OrderItem): TradeSettlementHint {
  const display = (order.displayStatus || '').toUpperCase();
  if (display === 'PAID_WAIT_GROUP') {
    return {
      label: '等待成团',
      detail: '拼团支付成功只表示名额已锁定，成团后才发放基础额度和赠送额度。',
      tone: 'warn',
    };
  }
  if (display === 'GROUP_FORMED') {
    return {
      label: '成团待到账',
      detail: '拼团已成团，额度发放中，请稍后刷新额度中心。',
      tone: 'warn',
    };
  }
  if (display === 'BENEFIT_GRANTED') {
    return {
      label: '已到账',
      detail: '额度已发放，可在额度中心查看余额。',
      tone: 'ready',
    };
  }
  if (display === 'WAIT_REFUND' || display === 'CLOSED') {
    return {
      label: display === 'WAIT_REFUND' ? '退款中' : '已关闭',
      detail: '订单已关闭或退款，请核对配额是否已回滚。',
      tone: 'danger',
    };
  }

  const status = (order.status || '').toLowerCase();
  const groupStatus = (order.groupStatus || '').toLowerCase();
  const isGroup = Boolean(groupStatus) || order.marketType === 1;

  if (status.includes('refund') || status === 'close') {
    return {
      label: '核对退款',
      detail: '订单已关闭或退款，请核对配额是否已回滚。',
      tone: 'danger',
    };
  }
  if (isGroup && (groupStatus === 'waiting' || WAITING_STATUSES.has(status))) {
    return {
      label: '等待成团',
      detail: '拼团支付成功只表示名额已锁定，成团后才发放基础额度和赠送额度。',
      tone: 'warn',
    };
  }
  if (isGroup && (groupStatus === 'formed' || DONE_STATUSES.has(status))) {
    return {
      label: '核对到账',
      detail: '拼团已成团或交易完成，请核对额度中心余额是否到账。',
      tone: 'ready',
    };
  }
  if (!isGroup && WAITING_STATUSES.has(status)) {
    return {
      label: '可到账',
      detail: '直购支付成功后额度应立即到账。',
      tone: 'ready',
    };
  }
  if (DONE_STATUSES.has(status)) {
    return {
      label: '已完成',
      detail: '交易已完成。',
      tone: 'ready',
    };
  }
  return {
    label: '待核查',
    detail: '请结合订单状态与额度中心余额判断。',
    tone: 'neutral',
  };
}

export function summarizeTradeWorkspace(
  summary: AccountSummary | null,
  orders: OrderItem[],
): TradeWorkspaceSummary {
  const groupOrders = orders.filter(
    (order) => order.marketType === 1 || Boolean(order.groupStatus),
  );
  // “待成团”只统计真正处于支付成功等待组队的订单。此前按 hint.tone=warn 统计，
  // 会把 GROUP_FORMED（权益发放中）也算进去，甚至在 GRANTED 后造成长期错误告警。
  const waitingGroupOrders = groupOrders.filter((order) => {
    const display = (order.displayStatus || '').toUpperCase();
    if (display) return display === 'PAID_WAIT_GROUP';
    return (order.groupStatus || '').toLowerCase() === 'waiting';
  });

  const consistencyHints: string[] = [];
  if (waitingGroupOrders.length > 0) {
    consistencyHints.push('存在支付成功但等待成团的拼团单，成团后才会发放额度。');
  }
  if (orders.length === 0) {
    consistencyHints.push('暂无订单，购买或参与拼团后这里会显示闭环状态。');
  }

  return {
    availableQuota: summary?.availableQuota ?? 0,
    freeQuota: summary?.freeQuotaBalance ?? 0,
    paidQuota: summary?.paidQuotaBalance ?? 0,
    frozenQuota: summary?.frozenBalance ?? 0,
    totalOrders: orders.length,
    groupOrders: groupOrders.length,
    waitingGroupOrders: waitingGroupOrders.length,
    recentOrders: orders.slice(0, 8),
    consistencyHints,
  };
}
