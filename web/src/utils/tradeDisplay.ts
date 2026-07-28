import type { GroupBuyTeam, GroupBuyTier, SkuItem } from '@/services/bff';

export type PackageTheme = {
  accent: string;
  accentSoft: string;
  accentText: string;
  gradient: string;
  ring: string;
};

const SKU_THEMES: Record<string, PackageTheme> = {
  QUOTA_LIGHT: {
    accent: 'bg-violet-600',
    accentSoft: 'bg-violet-50',
    accentText: 'text-violet-700',
    gradient: 'from-violet-500/15 via-violet-400/5 to-transparent',
    ring: 'ring-violet-200',
  },
  QUOTA_STANDARD: {
    accent: 'bg-sky-600',
    accentSoft: 'bg-sky-50',
    accentText: 'text-sky-700',
    gradient: 'from-sky-500/15 via-sky-400/5 to-transparent',
    ring: 'ring-sky-200',
  },
  QUOTA_LARGE: {
    accent: 'bg-emerald-600',
    accentSoft: 'bg-emerald-50',
    accentText: 'text-emerald-700',
    gradient: 'from-emerald-500/15 via-emerald-400/5 to-transparent',
    ring: 'ring-emerald-200',
  },
};

const DEFAULT_THEME: PackageTheme = {
  accent: 'bg-[var(--chat-text)]',
  accentSoft: 'bg-[var(--chat-surface-soft)]',
  accentText: 'text-[var(--chat-text)]',
  gradient: 'from-[var(--chat-surface-soft)] to-transparent',
  ring: 'ring-[var(--chat-border)]',
};

const SKU_DISPLAY_NAMES: Record<string, string> = {
  QUOTA_LIGHT: '轻享额度包',
  QUOTA_STANDARD: '标准额度包',
  QUOTA_LARGE: '大额额度包',
};

const SKU_DESCRIPTIONS: Record<string, string> = {
  QUOTA_LIGHT: '适合体验和低频使用，付费额度永久有效。',
  QUOTA_STANDARD: '适合日常技术学习与多轮对话。',
  QUOTA_LARGE: '适合高频使用，单点价格与大团赠送更划算。',
};

export function skuDisplayName(sku: Pick<SkuItem, 'code' | 'name'>): string {
  return sku.name || SKU_DISPLAY_NAMES[sku.code] || sku.code;
}

export function skuDescription(sku: Pick<SkuItem, 'code'>): string {
  return SKU_DESCRIPTIONS[sku.code] || '购买后即可在对话中使用，付费额度永久有效。';
}

export function skuTheme(code: string): PackageTheme {
  return SKU_THEMES[code] || DEFAULT_THEME;
}

export function formatPrice(price?: number): string {
  if (price == null) return '-';
  return `¥${price.toFixed(price % 1 === 0 ? 0 : 2)}`;
}

export function formatQuota(value?: number): string {
  if (value == null) return '-';
  return `${value} 点`;
}

/** 后端账本使用微额度，界面统一换算为额度点。 */
export function formatMicroQuota(value?: number): string {
  if (value == null) return '-';
  return `${(value / 1_000_000).toLocaleString('zh-CN', { maximumFractionDigits: 6 })} 点`;
}

export type QuotaLedgerAmountView = {
  text: string;
  tone: 'positive' | 'negative' | 'pending' | 'neutral';
};

/**
 * quota_ledger 的 amount 是领域流水，不全是余额增减：FREEZE 记录预留规模，
 * RELEASE 当前以 0 记录释放动作。直接按正负号展示会把“预留”误写成额度增加。
 */
export function quotaLedgerAmountView(type?: string, amount?: number): QuotaLedgerAmountView {
  const normalizedType = (type || '').toUpperCase();
  const normalizedAmount = Number.isFinite(amount) ? Number(amount) : 0;

  if (normalizedType === 'FREEZE') {
    return {
      text: `预留 ${formatMicroQuota(Math.abs(normalizedAmount))}`,
      tone: 'pending',
    };
  }
  if (normalizedType === 'RELEASE') {
    return {
      text:
        normalizedAmount === 0
          ? '预留已释放'
          : `释放 ${formatMicroQuota(Math.abs(normalizedAmount))}`,
      tone: 'neutral',
    };
  }

  return {
    text: `${normalizedAmount > 0 ? '+' : ''}${formatMicroQuota(normalizedAmount)}`,
    tone: normalizedAmount > 0 ? 'positive' : normalizedAmount < 0 ? 'negative' : 'neutral',
  };
}

export function shortTeamId(teamId?: string): string {
  if (!teamId) return '-';
  if (teamId.length <= 8) return teamId;
  return teamId.slice(0, 8).toUpperCase();
}

export function teamProgress(team: {
  completeCount?: number;
  lockCount?: number;
  targetCount?: number;
}) {
  const target = Number(team.targetCount ?? 0);
  const complete = Number(team.completeCount ?? team.lockCount ?? 0);
  const remaining = Math.max(target - complete, 0);
  const percent = target > 0 ? Math.min(100, Math.round((complete / target) * 100)) : 0;
  return {
    target,
    complete,
    remaining,
    percent,
  };
}

// ---------------- 阶梯拼团（tiered group-buy）展示辅助 ----------------

/** SKU 的基础永久额度，单位：额度点。 */
export function baseQuotaOf(sku: Pick<SkuItem, 'baseQuota'>): number {
  return Number(sku.baseQuota ?? 0);
}

export type QuotaLadderRow = {
  tierNo?: number;
  label: string;
  targetCount?: number;
  /** 该档位可得的总额度（基础额度 + 累计加赠） */
  total: number;
  /** 相对基础额度的累计加赠 */
  bonus: number;
  /** 是否最高档 */
  isMax: boolean;
  /** 是否"单独购买"行（无需拼团） */
  isSolo: boolean;
};

function sortedTiers(tiers?: GroupBuyTier[]): GroupBuyTier[] {
  return (tiers ?? [])
    .filter((t) => t && t.targetCount != null)
    .slice()
    .sort((a, b) => Number(a.targetCount ?? 0) - Number(b.targetCount ?? 0));
}

/** 构建"额度阶梯"表：单独购买(base) + 各人数档位(base+bonus)。无档位时返回空数组。 */
export function quotaLadder(sku: SkuItem, tiers?: GroupBuyTier[]): QuotaLadderRow[] {
  const list = sortedTiers(tiers ?? sku.groupTiers);
  if (list.length === 0) return [];
  const base = baseQuotaOf(sku);
  const rows: QuotaLadderRow[] = [
    { label: '单独购买', total: base, bonus: 0, isMax: false, isSolo: true },
  ];
  list.forEach((tier, index) => {
    rows.push({
      tierNo: tier.tierNo,
      label: tier.tierName ?? `${tier.targetCount}人团`,
      targetCount: tier.targetCount ?? undefined,
      total: base + Number(tier.bonusQuota ?? 0),
      bonus: Number(tier.bonusQuota ?? 0),
      isMax: index === list.length - 1,
      isSolo: false,
    });
  });
  return rows;
}

/** 是否阶梯额度拼团 */
export function isTieredSku(sku?: SkuItem | null): boolean {
  if (!sku) return false;
  if (sku.groupActivityType != null) return sku.groupActivityType === 1;
  return (sku.groupTiers?.length ?? 0) > 0;
}

export type TeamTierView = {
  isTiered: boolean;
  complete: number;
  /** 进度分母：最高档人数（无则回退目标人数） */
  maxTarget: number;
  /** 达成率（对最高档） */
  percent: number;
  /** 当前已达档位可得总额度 */
  currentQuota: number;
  /** 下一档可得总额度（已达最高档为 undefined） */
  nextQuota?: number;
  /** 升到下一档的额度提升量 */
  boost: number;
  /** 距离下一档还差人数（已达最高档为 0） */
  remainingToNext: number;
  /** 下一档所需人数 */
  nextTargetCount?: number;
  /** 是否已达最高档 */
  reachedMax: boolean;
};

/**
 * 计算某个团在阶梯模型下的展示信息（基于已支付人数投影当前/下一档额度）。
 * 依赖 team.reachedTierNo/nextTierTargetCount/maxTierTargetCount（后端计算）+ sku 的基础额度与档位。
 */
export function teamTierView(team: GroupBuyTeam, sku?: SkuItem | null): TeamTierView {
  const tiers = sortedTiers(team.tiers?.length ? team.tiers : sku?.groupTiers);
  const base = sku ? baseQuotaOf(sku) : 0;
  const complete = Number(team.completeCount ?? team.lockCount ?? 0);
  const reachedTierNo = Number(team.reachedTierNo ?? 0);

  let currentBonus = 0;
  let nextBonus: number | undefined;
  for (const tier of tiers) {
    const no = Number(tier.tierNo ?? 0);
    if (no <= reachedTierNo) {
      currentBonus = Number(tier.bonusQuota ?? currentBonus);
    } else if (nextBonus === undefined) {
      nextBonus = Number(tier.bonusQuota ?? 0);
    }
  }

  const maxTarget = Number(
    team.maxTierTargetCount ?? tiers[tiers.length - 1]?.targetCount ?? team.targetCount ?? 0,
  );
  const nextTargetCount = team.nextTierTargetCount ?? undefined;
  const currentQuota = base + currentBonus;
  const nextQuota = nextBonus !== undefined ? base + nextBonus : undefined;
  const reachedMax = nextQuota === undefined;
  const remainingToNext = nextTargetCount != null ? Math.max(nextTargetCount - complete, 0) : 0;
  const percent = maxTarget > 0 ? Math.min(100, Math.round((complete / maxTarget) * 100)) : 0;

  return {
    isTiered: tiers.length > 0,
    complete,
    maxTarget,
    percent,
    currentQuota,
    nextQuota,
    boost: nextQuota !== undefined ? nextQuota - currentQuota : 0,
    remainingToNext,
    nextTargetCount,
    reachedMax,
  };
}
