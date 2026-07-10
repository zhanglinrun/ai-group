import { memo } from 'react';
import { Clock3, Loader2, UserPlus } from 'lucide-react';
import type { GroupBuyTeam, SkuItem } from '@/services/bff';
import { useCountdown, COUNTDOWN_ENDED } from '@/hooks/useCountdown';
import {
  formatPrice,
  formatQuota,
  shortTeamId,
  skuDisplayName,
  skuTheme,
  teamProgress,
  teamTierView,
} from '@/utils/tradeDisplay';

type GroupTeamCardProps = {
  team: GroupBuyTeam;
  sku?: SkuItem | null;
  groupPrice?: number;
  buyingKey: string;
  onJoin: (teamId: string) => void;
};

const GroupTeamCard = memo(({ team, sku, groupPrice, buyingKey, onJoin }: GroupTeamCardProps) => {
  const teamId = team.teamId || '';
  const { target, complete, remaining, percent } = teamProgress(team);
  const tierView = teamTierView(team, sku);
  const tiered = tierView.isTiered;
  const theme = skuTheme(sku?.code || 'PRO_MONTH');
  const price = groupPrice ?? sku?.price;
  const joining = buyingKey === `${sku?.code || ''}-group-${teamId}`;
  const countdown = useCountdown(team.validEndTime, team.validTimeCountdown);
  const ended = countdown === COUNTDOWN_ENDED;

  return (
    <article className="overflow-hidden rounded-3xl border border-[var(--chat-border)] bg-white/80 shadow-[var(--shadow-sm)] backdrop-blur-sm dark:bg-white/5">
      <div className={`h-1.5 ${theme.accent}`} />
      <div className="p-5">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-base font-semibold">
                {sku ? skuDisplayName(sku) : 'Pro 会员拼团'}
              </span>
              <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
                进行中
              </span>
            </div>
            <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
              拼团码 {shortTeamId(teamId)}
            </div>
          </div>
          {countdown ? (
            <div
              className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium ${
                ended ? 'bg-gray-100 text-gray-500' : 'bg-amber-50 text-amber-800'
              }`}
            >
              <Clock3 className="h-3.5 w-3.5" />
              {ended ? COUNTDOWN_ENDED : `剩余 ${countdown}`}
            </div>
          ) : null}
        </div>

        <div className="mt-4">
          <div className="mb-2 flex items-center justify-between text-sm">
            <span className="font-medium">
              {tiered ? tierView.complete : complete}/{tiered ? tierView.maxTarget : target} 人
            </span>
            <span className="text-[var(--chat-text-soft)]">
              {tiered
                ? tierView.reachedMax
                  ? '已达最高档'
                  : `还差 ${tierView.remainingToNext} 人达成目标档`
                : `还差 ${remaining} 人成团`}
            </span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-[var(--chat-surface-soft)]">
            <div
              className={`h-full rounded-full transition-all ${theme.accent}`}
              style={{ width: `${tiered ? tierView.percent : percent}%` }}
            />
          </div>
        </div>

        {tiered ? (
          <div className="mt-4 grid grid-cols-3 gap-2 text-center text-xs">
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">当前额度</div>
              <div className="mt-1 font-medium">{formatQuota(tierView.currentQuota)}</div>
            </div>
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">奖励提升</div>
              <div className={`mt-1 font-medium ${tierView.reachedMax ? '' : 'text-orange-600'}`}>
                {tierView.reachedMax ? '已封顶' : `+${tierView.boost}`}
              </div>
            </div>
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">下一档额度</div>
              <div className="mt-1 font-medium">
                {tierView.nextQuota != null ? formatQuota(tierView.nextQuota) : '—'}
              </div>
            </div>
          </div>
        ) : sku?.periodQuota != null ? (
          <div className="mt-4 grid grid-cols-3 gap-2 text-center text-xs">
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">基础配额</div>
              <div className="mt-1 font-medium">{formatQuota(sku.periodQuota)}</div>
            </div>
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">拼团价</div>
              <div className="mt-1 font-medium">{formatPrice(price)}</div>
            </div>
            <div className="rounded-xl bg-[var(--chat-surface-soft)] px-2 py-2">
              <div className="text-[var(--chat-text-soft)]">成团后</div>
              <div className="mt-1 font-medium">自动开通</div>
            </div>
          </div>
        ) : null}

        <button
          type="button"
          onClick={() => onJoin(teamId)}
          disabled={Boolean(buyingKey) || remaining <= 0 || !teamId || ended}
          className={`mt-4 inline-flex w-full items-center justify-center gap-2 rounded-full px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60 ${theme.accent}`}
        >
          {joining ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <UserPlus className="h-4 w-4" />
          )}
          <span>
            {ended ? '拼团已结束' : `立即参团 ${price != null ? formatPrice(price) : ''}`}
          </span>
        </button>
      </div>
    </article>
  );
});

GroupTeamCard.displayName = 'GroupTeamCard';

export default GroupTeamCard;
