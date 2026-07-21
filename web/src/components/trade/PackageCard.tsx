import { memo } from 'react';
import { Coins, Loader2, TrendingUp, Users, Zap } from 'lucide-react';
import type { SkuItem } from '@/services/bff';
import {
  formatPrice,
  formatQuota,
  quotaLadder,
  skuDescription,
  skuDisplayName,
  skuTheme,
} from '@/utils/tradeDisplay';

type PackageCardProps = {
  sku: SkuItem;
  buyingKey: string;
  onDirectBuy?: () => void;
  onGroupBuy?: () => void;
  highlight?: boolean;
};

const PackageCard = memo(
  ({ sku, buyingKey, onDirectBuy, onGroupBuy, highlight }: PackageCardProps) => {
    const theme = skuTheme(sku.code);
    const directLoading = buyingKey === `${sku.code}-direct`;
    const groupLoading = buyingKey.startsWith(`${sku.code}-group`);
    const ladder = quotaLadder(sku);

    return (
      <article
        className={`relative overflow-hidden rounded-3xl border bg-white/80 p-6 shadow-[var(--shadow-sm)] backdrop-blur-sm transition hover:shadow-[var(--shadow-md)] dark:bg-white/5 ${
          highlight ? `border-transparent ring-2 ${theme.ring}` : 'border-[var(--chat-border)]'
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
                <Coins className="h-5 w-5" />
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
            <div className="text-3xl font-semibold tracking-tight">{formatPrice(sku.price)}</div>
            <div className="pb-1 text-sm text-[var(--chat-text-soft)]">直购 / 拼团同价</div>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
            <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-3 py-2.5">
              <div className="text-xs text-[var(--chat-text-soft)]">基础额度</div>
              <div className="mt-1 font-medium">{formatQuota(sku.baseQuota)}</div>
            </div>
            <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-3 py-2.5">
              <div className="text-xs text-[var(--chat-text-soft)]">有效期</div>
              <div className="mt-1 font-medium">永久有效</div>
            </div>
          </div>

          {ladder.length > 0 ? (
            <div className="mt-4 overflow-hidden rounded-2xl border border-[var(--chat-border)]">
              <div className="flex items-center justify-between border-b border-[var(--chat-border)] bg-[var(--chat-surface-soft)] px-3 py-2 text-sm font-medium">
                <span>额度阶梯</span>
                <span className={`inline-flex items-center gap-1 text-xs ${theme.accentText}`}>
                  人数越多额度越高
                  <TrendingUp className="h-3.5 w-3.5" />
                </span>
              </div>
              <div className="divide-y divide-[var(--chat-border)]">
                {ladder.map((row) => (
                  <div
                    key={row.label}
                    className="grid grid-cols-[1fr_auto_auto] items-center gap-2 px-3 py-1.5 text-sm"
                  >
                    <span className="font-medium">{row.label}</span>
                    <span className="justify-self-center whitespace-nowrap text-xs text-[var(--chat-text-soft)]">
                      {row.isSolo ? '单买' : `${row.targetCount} 人 · +${row.bonus}`}
                    </span>
                    <span
                      className={`justify-self-end font-semibold ${row.isMax ? theme.accentText : ''}`}
                    >
                      {formatQuota(row.total)}
                    </span>
                  </div>
                ))}
              </div>
              <div className="bg-[var(--chat-surface-soft)] px-3 py-1.5 text-[11px] text-[var(--chat-text-soft)]">
                未满目标时，按已达档位结算
              </div>
            </div>
          ) : null}

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
  },
);

PackageCard.displayName = 'PackageCard';

export default PackageCard;
