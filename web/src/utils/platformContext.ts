const PLATFORM_CONTEXT_TOOL = 'platform_context';

export type PlatformContextOperation = 'account_summary' | 'pricing' | 'group_buy' | 'orders';

export type PlatformNavigationCta = {
  label: string;
  path: string;
};

export type PlatformContextView = {
  operation: PlatformContextOperation;
  status: 'COMPLETE' | 'DEGRADED' | 'FAILED';
  complete: boolean;
  degraded: boolean;
  authoritativeEmpty: boolean;
  data?: Record<string, unknown>;
  cta?: PlatformNavigationCta;
  message?: string;
};

const OPERATIONS = new Set<PlatformContextOperation>([
  'account_summary',
  'pricing',
  'group_buy',
  'orders',
]);

const ALLOWED_CTA_PATHS = new Set(['/account', '/pricing', '/group-buy', '/orders']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseCta(value: unknown): PlatformNavigationCta | undefined {
  if (!isRecord(value) || typeof value.label !== 'string' || typeof value.path !== 'string') {
    return undefined;
  }
  const path = value.path.trim();
  if (!ALLOWED_CTA_PATHS.has(path) && !/^\/group-buy\/\d+$/.test(path)) {
    return undefined;
  }
  const label = value.label.trim();
  return label ? { label, path } : undefined;
}

type PlatformContextTaskLike = {
  messageType?: string;
  toolResult?: {
    toolName?: string;
    toolResult?: string;
  };
};

export function parsePlatformContextTask(task?: PlatformContextTaskLike): PlatformContextView | undefined {
  if (task?.messageType !== 'tool_result' || task.toolResult?.toolName !== PLATFORM_CONTEXT_TOOL) {
    return undefined;
  }

  let raw: unknown;
  try {
    raw = JSON.parse(task.toolResult.toolResult || '');
  } catch {
    return undefined;
  }
  if (!isRecord(raw) || !OPERATIONS.has(raw.operation as PlatformContextOperation)) {
    return undefined;
  }

  const status = raw.status;
  if (status !== 'COMPLETE' && status !== 'DEGRADED' && status !== 'FAILED') {
    return undefined;
  }

  return {
    operation: raw.operation as PlatformContextOperation,
    status,
    complete: raw.complete === true,
    degraded: raw.degraded === true,
    authoritativeEmpty: raw.authoritativeEmpty === true,
    data: isRecord(raw.data) ? raw.data : undefined,
    cta: parseCta(raw.cta),
    message: typeof raw.message === 'string' ? raw.message : undefined,
  };
}

export function platformContextItems(view: PlatformContextView): Array<{ label: string; value: string }> {
  const data = view.data || {};
  const number = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
  const listLength = (value: unknown) => (Array.isArray(value) ? value.length : 0);

  switch (view.operation) {
    case 'account_summary': {
      const formatQuota = (value: unknown) => {
        const amount = number(value);
        return amount == null
          ? '-'
          : `${(amount / 1_000_000).toLocaleString('zh-CN', { maximumFractionDigits: 6 })} 点`;
      };
      return [
        { label: '可用额度', value: formatQuota(data.availableQuota) },
        { label: '免费额度', value: formatQuota(data.freeQuotaBalance) },
        { label: '付费额度', value: formatQuota(data.paidQuotaBalance) },
        { label: '冻结额度', value: formatQuota(data.frozenBalance) },
      ];
    }
    case 'pricing':
      return [
        { label: '可选套餐', value: `${listLength(data.skus)} 个` },
        {
          label: '拼团能力',
          value: isRecord(data.groupBuy) && data.groupBuy.unavailable !== true ? '可用' : '暂不可用',
        },
      ];
    case 'group_buy': {
      const groupBuy = isRecord(data.groupBuy) ? data.groupBuy : {};
      return [
        { label: '可拼套餐', value: `${listLength(data.skus)} 个` },
        { label: '进行中团队', value: `${listLength(groupBuy.teamList)} 个` },
      ];
    }
    case 'orders':
      return [{ label: '最近订单', value: `${listLength(data.items)} 笔` }];
  }
}
