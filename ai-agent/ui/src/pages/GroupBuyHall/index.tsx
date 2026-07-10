import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Loader2, RefreshCw, Users } from 'lucide-react';
import { message } from 'antd';
import ShellNav from '@/components/ShellNav';
import GroupTeamCard from '@/components/trade/GroupTeamCard';
import { bffApi, type GroupBuyInfo, type GroupBuyTeam, type SkuItem } from '@/services/bff';
import { useTradePurchase } from '@/hooks/useTradePurchase';
import { ROUTES } from '@/router/routes';
import { isMemberSku, skuDisplayName } from '@/utils/tradeDisplay';

const GroupBuyHallPage = memo(() => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [groupBuy, setGroupBuy] = useState<GroupBuyInfo | null>(null);
  const [skus, setSkus] = useState<SkuItem[]>([]);
  const { buyingKey, handleBuy } = useTradePurchase(groupBuy);

  const loadHall = useCallback(async () => {
    setLoading(true);
    try {
      const data = await bffApi.getPricing();
      setGroupBuy(data?.groupBuy || null);
      setSkus(data?.skus || []);
    } catch (error) {
      console.error('加载拼团大厅失败', error);
      message.error('拼团大厅加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadHall();
  }, [loadHall]);

  const memberSkus = useMemo(
    () => skus.filter((sku) => isMemberSku(sku) && sku.code !== 'FREE'),
    [skus],
  );
  const defaultSku = memberSkus[0] ?? null;
  const teamList = groupBuy?.teamList ?? [];
  const canGroupBuy = groupBuy?.activityId != null;

  // 大厅聚合了各 SKU 活动的队伍：按 team.activityId 归属到对应套餐（月卡/年卡/加油包）
  const skuByActivityId = useMemo(() => {
    const map = new Map<number, SkuItem>();
    for (const sku of skus) {
      if (sku.groupActivityId != null) {
        map.set(sku.groupActivityId, sku);
      }
    }
    return map;
  }, [skus]);

  const resolveTeamSku = useCallback(
    (team: GroupBuyTeam): SkuItem | null => {
      if (team.activityId != null) {
        return skuByActivityId.get(team.activityId) ?? defaultSku;
      }
      return defaultSku;
    },
    [skuByActivityId, defaultSku],
  );

  const handleJoinTeam = useCallback(
    async (team: GroupBuyTeam) => {
      const teamSku = resolveTeamSku(team);
      if (!teamSku) {
        message.error('暂无可用套餐');
        return;
      }
      const ok = await handleBuy(teamSku, 'group', team.teamId);
      if (ok) {
        navigate(`${ROUTES.PRICING}?tab=orders`);
      }
    },
    [resolveTeamSku, handleBuy, navigate],
  );

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="flex flex-col gap-4 rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-md)] lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h1 className="font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              拼团大厅
            </h1>
            <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
              {canGroupBuy
                ? `共 ${teamList.length} 个进行中的拼团，参团享优惠，成团后自动开通会员`
                : '拼团活动暂不可用，可前往购买页直接购买会员或额度包'}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void loadHall()}
              disabled={loading}
              className="inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm disabled:opacity-60"
            >
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </button>
            <Link
              to={ROUTES.PRICING}
              className="inline-flex items-center gap-2 rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white"
            >
              <Users className="h-4 w-4" />
              发起新团
            </Link>
          </div>
        </div>

        <div className="mt-4 rounded-2xl border border-amber-200/80 bg-amber-50/80 px-4 py-3 text-sm text-amber-900">
          拼团购买支付成功后需等待成团，成团后自动开通 Pro 会员并发放周期配额；也可在
          <Link to={ROUTES.PRICING} className="mx-1 font-medium underline">
            购买套餐
          </Link>
          选择直接购买，支付后立即生效。
        </div>

        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
          </div>
        ) : !canGroupBuy ? (
          <div className="mt-6 rounded-3xl border border-dashed border-[var(--chat-border)] px-6 py-16 text-center">
            <div className="text-base font-medium">拼团活动暂不可用</div>
            <p className="mt-2 text-sm text-[var(--chat-text-soft)]">
              你可以先选择直接购买会员或额度包
            </p>
            <Link
              to={ROUTES.PRICING}
              className="mt-4 inline-flex rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white"
            >
              前往购买
            </Link>
          </div>
        ) : teamList.length === 0 ? (
          <div className="mt-6 rounded-3xl border border-dashed border-[var(--chat-border)] px-6 py-16 text-center">
            <div className="text-base font-medium">暂无进行中的拼团</div>
            <p className="mt-2 text-sm text-[var(--chat-text-soft)]">
              成为第一个开团的人，或选择直接购买
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-3">
              <Link
                to={ROUTES.PRICING}
                className="inline-flex rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white"
              >
                发起新团
              </Link>
              <Link
                to={ROUTES.PRICING}
                className="inline-flex rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm"
              >
                直接购买
              </Link>
            </div>
          </div>
        ) : (
          <div className="mt-6 grid gap-4 md:grid-cols-2">
            {teamList.map((team) => {
              const teamSku = resolveTeamSku(team);
              return (
                <GroupTeamCard
                  key={team.teamId}
                  team={team}
                  sku={teamSku}
                  groupPrice={teamSku?.groupPayPrice ?? groupBuy?.goods?.payPrice}
                  buyingKey={buyingKey}
                  onJoin={() => void handleJoinTeam(team)}
                />
              );
            })}
          </div>
        )}

        {defaultSku && canGroupBuy ? (
          <div className="mt-6 text-center text-sm text-[var(--chat-text-soft)]">
            当前参团默认套餐：
            <span className="font-medium text-[var(--chat-text)]">
              {skuDisplayName(defaultSku)}
            </span>
            ，如需选择其他套餐请前往
            <button
              type="button"
              onClick={() =>
                navigate(`${ROUTES.GROUP_BUY_HALL}/${groupBuy?.activityId}`, {
                  state: { skuCode: defaultSku.code },
                })
              }
              className="mx-1 font-medium text-violet-700 underline"
            >
              拼团详情页
            </button>
          </div>
        ) : null}
      </main>
    </div>
  );
});

GroupBuyHallPage.displayName = 'GroupBuyHallPage';

export default GroupBuyHallPage;
