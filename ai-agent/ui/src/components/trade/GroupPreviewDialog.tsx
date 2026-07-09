import { memo } from "react";
import { ArrowLeft, CreditCard, Loader2, UserPlus, X } from "lucide-react";
import type { GroupBuyInfo, GroupBuyTeam, SkuItem } from "@/services/bff";
import {
  formatPrice,
  formatQuota,
  shortTeamId,
  skuDisplayName,
  skuTheme,
  teamProgress,
} from "@/utils/tradeDisplay";

type GroupPreviewDialogProps = {
  sku: SkuItem;
  groupBuy: GroupBuyInfo | null;
  loading: boolean;
  buyingKey: string;
  onClose: () => void;
  onBuy: (teamId?: string) => void;
};

const TeamRow = memo(
  ({
    team,
    goodsKey,
    buyingKey,
    themeAccent,
    onBuy,
  }: {
    team: GroupBuyTeam;
    goodsKey: string;
    buyingKey: string;
    themeAccent: string;
    onBuy: (teamId: string) => void;
  }) => {
    const teamId = team.teamId || "";
    const { target, complete, remaining, percent } = teamProgress(team);
    const joining = buyingKey === `${goodsKey}-group-${teamId}`;

    return (
      <article className="rounded-2xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/70 p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-sm font-medium">队伍 {shortTeamId(teamId)}</div>
            <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
              已支付 {complete}/{target} 人
              {team.validTimeCountdown ? ` · 剩余 ${team.validTimeCountdown}` : ""}
            </div>
          </div>
          <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
            还差 {remaining} 人
          </span>
        </div>
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-[var(--chat-surface-soft)]">
          <div
            className={`h-full rounded-full ${themeAccent}`}
            style={{ width: `${percent}%` }}
          />
        </div>
        <button
          type="button"
          onClick={() => onBuy(teamId)}
          disabled={Boolean(buyingKey) || remaining <= 0}
          className="mt-3 inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] px-3 py-1.5 text-xs font-medium disabled:opacity-60"
        >
          {joining ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <UserPlus className="h-3.5 w-3.5" />
          )}
          <span>加入这个团</span>
        </button>
      </article>
    );
  }
);
TeamRow.displayName = "TeamRow";

const GroupPreviewDialog = memo(
  ({ sku, groupBuy, loading, buyingKey, onClose, onBuy }: GroupPreviewDialogProps) => {
    const goods = groupBuy?.goods;
    const theme = skuTheme(sku.code);
    const groupPrice = goods?.payPrice ?? sku.price;
    const originPrice = goods?.originalPrice ?? sku.price;
    const teamList = groupBuy?.teamList ?? [];
    const goodsKey = sku.code;
    const starting = buyingKey === `${goodsKey}-group`;

    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-4 py-6 backdrop-blur-sm">
        <div className="w-full max-w-3xl rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)] p-6 shadow-[var(--shadow-lg)]">
          <div className="mb-5 flex items-center justify-between gap-3">
            <button
              type="button"
              onClick={onClose}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-[var(--chat-border)]"
            >
              <ArrowLeft className="h-4 w-4" />
            </button>
            <div className="min-w-0 flex-1">
              <div className="truncate text-lg font-medium">{skuDisplayName(sku)}</div>
              <div className="mt-1 text-sm text-[var(--chat-text-soft)]">
                拼团支付成功后等待成团；成团后自动开通 Pro 并发放配额。
              </div>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-[var(--chat-border)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="grid gap-4 md:grid-cols-[0.9fr_1.1fr]">
            <section className={`rounded-2xl border border-[var(--chat-border)] bg-gradient-to-br ${theme.gradient} p-5`}>
              <div className="text-sm text-[var(--chat-text-soft)]">拼团价格</div>
              <div className="mt-2 text-3xl font-semibold">{formatPrice(groupPrice)}</div>
              <div className="mt-2 text-sm text-[var(--chat-text-soft)]">
                原价 {formatPrice(originPrice)}
                {goods?.deductionPrice != null && goods.deductionPrice > 0 ? (
                  <span className="ml-2 font-medium text-emerald-600">
                    省 {formatPrice(goods.deductionPrice)}
                  </span>
                ) : null}
              </div>
              <div className="mt-4 grid grid-cols-2 gap-2 text-sm">
                {sku.periodQuota != null ? (
                  <div className="rounded-xl bg-white/70 px-3 py-2 dark:bg-white/10">
                    <div className="text-xs text-[var(--chat-text-soft)]">周期配额</div>
                    <div className="mt-1 font-medium">{formatQuota(sku.periodQuota)}</div>
                  </div>
                ) : null}
                {sku.memberDays != null ? (
                  <div className="rounded-xl bg-white/70 px-3 py-2 dark:bg-white/10">
                    <div className="text-xs text-[var(--chat-text-soft)]">有效期</div>
                    <div className="mt-1 font-medium">{sku.memberDays} 天</div>
                  </div>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => onBuy()}
                disabled={Boolean(buyingKey)}
                className={`mt-5 inline-flex w-full items-center justify-center gap-2 rounded-full px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60 ${theme.accent}`}
              >
                {starting ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <UserPlus className="h-4 w-4" />
                )}
                <span>自己开团</span>
              </button>
            </section>

            <section className="rounded-2xl border border-[var(--chat-border)] bg-white/50 p-5 dark:bg-white/5">
              <div className="mb-3 text-base font-medium">可加入拼团</div>
              {loading ? (
                <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-6 text-sm text-[var(--chat-text-soft)]">
                  拼团列表读取中...
                </div>
              ) : teamList.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-6 text-sm text-[var(--chat-text-soft)]">
                  暂无可加入队伍，可以自己开团。
                </div>
              ) : (
                <div className="max-h-72 space-y-3 overflow-y-auto">
                  {teamList.map((team) => (
                    <TeamRow
                      key={team.teamId}
                      team={team}
                      goodsKey={goodsKey}
                      buyingKey={buyingKey}
                      themeAccent={theme.accent}
                      onBuy={onBuy}
                    />
                  ))}
                </div>
              )}
            </section>
          </div>

          <div className="mt-4 flex items-center gap-2 rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-3 text-xs text-[var(--chat-text-soft)]">
            <CreditCard className="h-3.5 w-3.5 shrink-0" />
            <span>支付将跳转支付宝沙箱，完成后请回到「订单记录」查看成团进度。</span>
          </div>
        </div>
      </div>
    );
  }
);

GroupPreviewDialog.displayName = "GroupPreviewDialog";

export default GroupPreviewDialog;
