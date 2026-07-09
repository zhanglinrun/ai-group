import { memo } from "react";
import { Crown, Loader2, Sparkles, Users, Zap } from "lucide-react";
import type { SkuItem } from "@/services/bff";
import {
  formatPrice,
  formatQuota,
  isMemberSku,
  skuDescription,
  skuDisplayName,
  skuTheme,
} from "@/utils/tradeDisplay";

type PackageCardProps = {
  sku: SkuItem;
  groupPrice?: number;
  deductionPrice?: number;
  buyingKey: string;
  onDirectBuy?: () => void;
  onGroupBuy?: () => void;
  highlight?: boolean;
};

const PackageCard = memo(
  ({
    sku,
    groupPrice,
    deductionPrice,
    buyingKey,
    onDirectBuy,
    onGroupBuy,
    highlight,
  }: PackageCardProps) => {
    const theme = skuTheme(sku.code);
    const member = isMemberSku(sku);
    const directLoading = buyingKey === `${sku.code}-direct`;
    const groupLoading = buyingKey.startsWith(`${sku.code}-group`);

    return (
      <article
        className={`relative overflow-hidden rounded-3xl border bg-white/80 p-6 shadow-[var(--shadow-sm)] backdrop-blur-sm transition hover:shadow-[var(--shadow-md)] dark:bg-white/5 ${
          highlight
            ? `border-transparent ring-2 ${theme.ring}`
            : "border-[var(--chat-border)]"
        }`}
      >
        <div
          className={`pointer-events-none absolute inset-x-0 top-0 h-28 bg-gradient-to-b ${theme.gradient}`}
        />

        <div className="relative">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <div
                className={`flex h-11 w-11 items-center justify-center rounded-2xl text-white ${theme.accent}`}
              >
                {member ? (
                  <Crown className="h-5 w-5" />
                ) : (
                  <Zap className="h-5 w-5" />
                )}
              </div>
              <div>
                <div className="text-lg font-semibold">{skuDisplayName(sku)}</div>
                <div className="mt-0.5 text-sm text-[var(--chat-text-soft)]">
                  {skuDescription(sku)}
                </div>
              </div>
            </div>
            {highlight ? (
              <span
                className={`rounded-full px-2.5 py-1 text-xs font-medium ${theme.accentSoft} ${theme.accentText}`}
              >
                推荐
              </span>
            ) : null}
          </div>

          <div className="mt-5 flex items-end gap-2">
            <div className="text-3xl font-semibold tracking-tight">
              {formatPrice(sku.price)}
            </div>
            {groupPrice != null && groupPrice !== sku.price ? (
              <div className="pb-1 text-sm text-[var(--chat-text-soft)]">
                拼团 {formatPrice(groupPrice)}
              </div>
            ) : null}
          </div>

          {deductionPrice != null && deductionPrice > 0 ? (
            <div className="mt-1 inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700">
              <Sparkles className="h-3.5 w-3.5" />
              拼团最高省 {formatPrice(deductionPrice)}
            </div>
          ) : null}

          <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
            {sku.periodQuota != null && sku.periodQuota > 0 ? (
              <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-3 py-2.5">
                <div className="text-xs text-[var(--chat-text-soft)]">周期配额</div>
                <div className="mt-1 font-medium">{formatQuota(sku.periodQuota)}</div>
              </div>
            ) : null}
            {sku.topupQuota != null && sku.topupQuota > 0 ? (
              <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-3 py-2.5">
                <div className="text-xs text-[var(--chat-text-soft)]">加油包额度</div>
                <div className="mt-1 font-medium">{formatQuota(sku.topupQuota)}</div>
              </div>
            ) : null}
            {sku.memberDays != null && sku.memberDays > 0 ? (
              <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-3 py-2.5">
                <div className="text-xs text-[var(--chat-text-soft)]">有效期</div>
                <div className="mt-1 font-medium">{sku.memberDays} 天</div>
              </div>
            ) : null}
          </div>

          <div className="mt-5 flex flex-wrap gap-2">
            {onDirectBuy ? (
              <button
                type="button"
                onClick={onDirectBuy}
                disabled={Boolean(buyingKey)}
                className={`inline-flex flex-1 min-w-[120px] items-center justify-center gap-2 rounded-full border border-[var(--chat-border)] px-4 py-2.5 text-sm font-medium transition hover:bg-[var(--chat-surface-soft)] disabled:opacity-60`}
              >
                {directLoading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Zap className="h-4 w-4" />
                )}
                <span>直接购买</span>
              </button>
            ) : null}
            {onGroupBuy ? (
              <button
                type="button"
                onClick={onGroupBuy}
                disabled={Boolean(buyingKey)}
                className={`inline-flex flex-1 min-w-[120px] items-center justify-center gap-2 rounded-full px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60 ${theme.accent}`}
              >
                {groupLoading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Users className="h-4 w-4" />
                )}
                <span>拼团购买</span>
              </button>
            ) : null}
          </div>
        </div>
      </article>
    );
  }
);

PackageCard.displayName = "PackageCard";

export default PackageCard;
