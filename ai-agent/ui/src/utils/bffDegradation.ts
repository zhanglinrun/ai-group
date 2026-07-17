import type { BffMeta } from '@/services/bff';

const SERVICE_LABELS: Record<string, string> = {
  member: '额度服务',
  group: '拼团服务',
  pay: '订单支付服务',
};

export function bffDegradationMessage(...metas: Array<BffMeta | null | undefined>): string {
  const degraded = metas.filter((meta): meta is BffMeta => Boolean(meta?.degraded));
  if (degraded.length === 0) return '';

  const services = Array.from(
    new Set(
      degraded.flatMap((meta) =>
        (meta.errors || [])
          .map((error) => (error.service || '').trim().toLowerCase())
          .filter(Boolean),
      ),
    ),
  ).map((service) => SERVICE_LABELS[service] || service);

  const serviceText = services.length > 0 ? `（${services.join('、')}）` : '';
  return `部分数据暂不可用${serviceText}，当前页面可能不完整，请稍后刷新。`;
}
