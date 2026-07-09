import type { SkuItem } from "@/services/bff";

export type PackageTheme = {
  accent: string;
  accentSoft: string;
  accentText: string;
  gradient: string;
  ring: string;
};

const SKU_THEMES: Record<string, PackageTheme> = {
  PRO_MONTH: {
    accent: "bg-violet-600",
    accentSoft: "bg-violet-50",
    accentText: "text-violet-700",
    gradient: "from-violet-500/15 via-violet-400/5 to-transparent",
    ring: "ring-violet-200",
  },
  PRO_YEAR: {
    accent: "bg-sky-600",
    accentSoft: "bg-sky-50",
    accentText: "text-sky-700",
    gradient: "from-sky-500/15 via-sky-400/5 to-transparent",
    ring: "ring-sky-200",
  },
  TOPUP_200: {
    accent: "bg-emerald-600",
    accentSoft: "bg-emerald-50",
    accentText: "text-emerald-700",
    gradient: "from-emerald-500/15 via-emerald-400/5 to-transparent",
    ring: "ring-emerald-200",
  },
};

const DEFAULT_THEME: PackageTheme = {
  accent: "bg-[var(--chat-text)]",
  accentSoft: "bg-[var(--chat-surface-soft)]",
  accentText: "text-[var(--chat-text)]",
  gradient: "from-[var(--chat-surface-soft)] to-transparent",
  ring: "ring-[var(--chat-border)]",
};

const SKU_DISPLAY_NAMES: Record<string, string> = {
  PRO_MONTH: "Pro 月卡",
  PRO_YEAR: "Pro 年卡",
  TOPUP_200: "额度加油包",
  FREE: "Free 体验版",
};

const SKU_DESCRIPTIONS: Record<string, string> = {
  PRO_MONTH: "按月开通 Pro 会员，享受更高周期配额与专属权益。",
  PRO_YEAR: "年度 Pro 会员，更长有效期，适合持续深度使用。",
  TOPUP_200: "一次性购买加油包额度，支付成功后立即到账。",
};

export function skuDisplayName(sku: Pick<SkuItem, "code" | "name">): string {
  return SKU_DISPLAY_NAMES[sku.code] || sku.name || sku.code;
}

export function skuDescription(sku: Pick<SkuItem, "code">): string {
  return SKU_DESCRIPTIONS[sku.code] || "开通后即可在对话中使用对应配额。";
}

export function skuTheme(code: string): PackageTheme {
  return SKU_THEMES[code] || DEFAULT_THEME;
}

export function isMemberSku(sku: SkuItem): boolean {
  if (sku.skuType) {
    return sku.skuType.toUpperCase() === "MEMBER";
  }
  return sku.code.startsWith("PRO_");
}

export function isTopupSku(sku: SkuItem): boolean {
  if (sku.skuType) {
    return sku.skuType.toUpperCase() === "TOPUP";
  }
  return sku.code.startsWith("TOPUP_");
}

export function tierLabel(tier?: string): string {
  const normalized = (tier || "FREE").toUpperCase();
  if (normalized === "PRO") {
    return "Pro 会员";
  }
  return "Free 用户";
}

export function formatPrice(price?: number): string {
  if (price == null) return "-";
  return `¥${price.toFixed(price % 1 === 0 ? 0 : 2)}`;
}

export function formatQuota(value?: number): string {
  if (value == null) return "-";
  return `${value} 点`;
}

export function shortTeamId(teamId?: string): string {
  if (!teamId) return "-";
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
    percent
  };
}
