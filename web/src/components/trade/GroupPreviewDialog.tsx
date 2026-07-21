import { memo } from 'react';
import { ArrowLeft, CreditCard, Loader2, UserPlus, X } from 'lucide-react';
import type { GroupBuyInfo, GroupBuyTeam, SkuItem } from '@/services/bff';
import { useCountdown, COUNTDOWN_ENDED } from '@/hooks/useCountdown';
import {
  formatPrice,
  formatQuota,
  quotaLadder,
  shortTeamId,
  skuDisplayName,
  skuTheme,
  teamProgress,
  teamTierView,
} from '@/utils/tradeDisplay';

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
    sku,
    goodsKey,
    buyingKey,
    themeAccent,
    onBuy,
  }: {
    team: GroupBuyTeam;
    sku: SkuItem;
    goodsKey: string;
    buyingKey: string;
    themeAccent: string;
    onBuy: (teamId: string) => void;
  }) => {
    const teamId = team.teamId || '';
    const { target, complete, remaining, percent } = teamProgress(team);
    const tierView = teamTierView(team, sku);
    const tiered = tierView.isTiered;
    const joining = buyingKey === `${goodsKey}-group-${teamId}`;
    const countdown = useCountdown(team.validEndTime, team.validTimeCountdown);
    const ended = countdown === COUNTDOWN_ENDED;

    return (
      <article className="rounded-2xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/70 p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-sm font-medium">队伍 {shortTeamId(teamId)}</div>
            <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
              已支付 {tiered ? tierView.complete : complete}/{tiered ? tierView.maxTarget : target}{' '}
              人{countdown ? (ended ? ` · ${COUNTDOWN_ENDED}` : ` · 剩余 ${countdown}`) : ''}
            </div>
          </div>
          <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
            {tiered
              ? tierView.reachedMax
                ? '已达最高档'
                : `还差 ${tierView.remainingToNext} 人升档`
              : `还差 ${remaining} 人`}
          </span>
        </div>
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-[var(--chat-surface-soft)]">
          <div
            className={`h-full rounded-full ${themeAccent}`}
            style={{ width: `${tiered ? tierView.percent : percent}%` }}
          />
        </div>
        {tiered ? (
          <div className="mt-3 flex items-center justify-between rounded-xl bg-[var(--chat-surface-soft)] px-3 py-2 text-xs">
            <span className="text-[var(--chat-text-soft)]">
              当前{' '}
              <span className="font-medium text-[var(--chat-text)]">
                {formatQuota(tierView.currentQuota)}
              </span>
            </span>
            {tierView.reachedMax ? (
              <span className="font-medium text-emerald-600">已封顶</span>
            ) : (
              <span className="text-[var(--chat-text-soft)]">
                下一档{' '}
                <span className="font-medium text-[var(--chat-text)]">
                  {formatQuota(tierView.nextQuota)}
                </span>
                <span className="ml-1 text-orange-600">+{tierView.boost}</span>
              </span>
            )}
          </div>
        ) : null}
        <button
          type="button"
          onClick={() => onBuy(teamId)}
          disabled={Boolean(buyingKey) || remaining <= 0 || ended}
          className="mt-3 inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] px-3 py-1.5 text-xs font-medium disabled:opacity-60"
        >
          {joining ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <UserPlus className="h-3.5 w-3.5" />
          )}
          <span>{ended ? '拼团已结束' : '加入这个团'}</span>
        </button>
      </article>
    );
  },
);
TeamRow.displayName = 'TeamRow';

const GroupPreviewDialog = memo(
  ({ sku, groupBuy, loading, buyingKey, onClose, onBuy }: GroupPreviewDialogProps) => {
    const theme = skuTheme(sku.code);
    // 只展示属于该额度包活动的队伍，避免跨套餐加错团
    const teamList = (groupBuy?.teamList ?? []).filter(
      (team) =>
        sku.groupActivityId == null ||
        team.activityId == null ||
        team.activityId === sku.groupActivityId,
    );
    const goodsKey = sku.code;
    const starting = buyingKey === `${goodsKey}-group`;
    const ladder = quotaLadder(sku);

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
                拼团与直购同价；成团后按人数档位发放基础额度和赠送额度。
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
            <section
              className={`rounded-2xl border border-[var(--chat-border)] bg-gradient-to-br ${theme.gradient} p-5`}
            >
              <div className="text-sm text-[var(--chat-text-soft)]">直购 / 拼团同价</div>
              <div className="mt-2 text-3xl font-semibold">{formatPrice(sku.price)}</div>
              <div className="mt-2 text-sm text-[var(--chat-text-soft)]">
                人数越多，赠送额度越多
              </div>
              <div className="mt-4 grid grid-cols-2 gap-2 text-sm">
                {sku.baseQuota != null ? (
                  <div className="rounded-xl bg-white/70 px-3 py-2 dark:bg-white/10">
                    <div className="text-xs text-[var(--chat-text-soft)]">基础额度</div>
                    <div className="mt-1 font-medium">{formatQuota(sku.baseQuota)}</div>
                  </div>
                ) : null}
                <div className="rounded-xl bg-white/70 px-3 py-2 dark:bg-white/10">
                  <div className="text-xs text-[var(--chat-text-soft)]">有效期</div>
                  <div className="mt-1 font-medium">永久有效</div>
                </div>
              </div>
              {ladder.length > 0 ? (
                <div className="mt-4 overflow-hidden rounded-xl border border-white/60 bg-white/60 dark:bg-white/5">
                  <div className="flex items-center justify-between border-b border-white/60 px-3 py-1.5 text-xs font-medium">
                    <span>额度阶梯</span>
                    <span className={theme.accentText}>人数越多额度越高</span>
                  </div>
                  <div className="divide-y divide-white/50">
                    {ladder.map((row) => (
                      <div
                        key={row.label}
                        className="grid grid-cols-[1fr_auto] items-center gap-2 px-3 py-1 text-xs"
                      >
                        <span>
                          {row.label}
                          {row.isSolo ? '' : ` · +${row.bonus}`}
                        </span>
                        <span className={`font-semibold ${row.isMax ? theme.accentText : ''}`}>
                          {formatQuota(row.total)}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
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
                      sku={sku}
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
            <span>下单后将弹出支付二维码，完成后请到「订单记录」查看成团进度。</span>
          </div>
        </div>
      </div>
    );
  },
);

GroupPreviewDialog.displayName = 'GroupPreviewDialog';

export default GroupPreviewDialog;
