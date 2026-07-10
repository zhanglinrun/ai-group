import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  Check,
  Clock3,
  Crown,
  Loader2,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  UserPlus,
  Zap,
} from 'lucide-react';
import ShellNav from '@/components/ShellNav';
import GroupTeamCard from '@/components/trade/GroupTeamCard';
import PaymentQrDialog from '@/components/trade/PaymentQrDialog';
import { bffApi, type GroupBuyInfo, type SkuItem } from '@/services/bff';
import { useTradePurchase } from '@/hooks/useTradePurchase';
import { ROUTES } from '@/router/routes';
import {
  formatPrice,
  formatQuota,
  isMemberSku,
  isTieredSku,
  quotaLadder,
  skuDescription,
  skuDisplayName,
  skuTheme,
} from '@/utils/tradeDisplay';

const GroupBuyPage = memo(() => {
  const { activityId: activityIdParam } = useParams<{ activityId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const preferredSkuCode = (location.state as { skuCode?: string } | null)?.skuCode;
  const activityId = Number(activityIdParam);
  const [loading, setLoading] = useState(true);
  const [groupBuy, setGroupBuy] = useState<GroupBuyInfo | null>(null);
  const [skus, setSkus] = useState<SkuItem[]>([]);
  const [selectedCode, setSelectedCode] = useState<string>('');
  const { buyingKey, handleBuy, qrPayment, closeQrPayment } = useTradePurchase(groupBuy);

  useEffect(() => {
    if (!Number.isFinite(activityId)) {
      setLoading(false);
      return;
    }
    bffApi
      .getGroupBuy(activityId)
      .then((data) => {
        setGroupBuy(data?.groupBuy || null);
        setSkus((data?.skus || []).filter((sku) => sku.code !== 'FREE'));
      })
      .catch((error) => console.error('加载拼团活动失败', error))
      .finally(() => setLoading(false));
  }, [activityId]);

  const memberSkus = useMemo(() => skus.filter((sku) => isMemberSku(sku)), [skus]);

  const selectedSku = useMemo(() => {
    if (selectedCode) {
      const matched = memberSkus.find((sku) => sku.code === selectedCode);
      if (matched) return matched;
    }
    if (preferredSkuCode) {
      const matched = memberSkus.find((sku) => sku.code === preferredSkuCode);
      if (matched) return matched;
    }
    return memberSkus[0] ?? null;
  }, [selectedCode, preferredSkuCode, memberSkus]);

  useEffect(() => {
    if (selectedSku && !selectedCode) {
      setSelectedCode(selectedSku.code);
    }
  }, [selectedSku, selectedCode]);

  const theme = skuTheme(selectedSku?.code || 'PRO_MONTH');
  const groupPrice = groupBuy?.goods?.payPrice ?? selectedSku?.price;
  const originPrice = groupBuy?.goods?.originalPrice ?? selectedSku?.price;
  const ladder = selectedSku ? quotaLadder(selectedSku) : [];
  const tiered = isTieredSku(selectedSku);

  const handleDirectBuy = useCallback(async () => {
    if (!selectedSku) return;
    await handleBuy(selectedSku, 'direct');
  }, [selectedSku, handleBuy]);

  const handleGroupStart = useCallback(async () => {
    if (!selectedSku) return;
    await handleBuy(selectedSku, 'group');
  }, [selectedSku, handleBuy]);

  const handleJoinTeam = useCallback(
    async (teamId: string) => {
      if (!selectedSku) return;
      await handleBuy(selectedSku, 'group', teamId);
    },
    [selectedSku, handleBuy],
  );

  if (!Number.isFinite(activityId)) {
    return (
      <div className="min-h-screen bg-[var(--page-gradient)]">
        <ShellNav />
        <main className="mx-auto max-w-6xl px-4 py-8 text-center">
          <div className="text-base font-medium">活动 ID 无效</div>
          <Link
            to={ROUTES.GROUP_BUY_HALL}
            className="mt-4 inline-block text-sm text-violet-700 underline"
          >
            返回拼团大厅
          </Link>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <div>
            <Link
              to={ROUTES.GROUP_BUY_HALL}
              className="text-sm text-[var(--chat-text-soft)] hover:text-[var(--chat-text)]"
            >
              ← 返回拼团大厅
            </Link>
            <h1 className="mt-2 font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              拼团购买
            </h1>
          </div>
        </div>

        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
          </div>
        ) : !groupBuy || !selectedSku ? (
          <div className="rounded-3xl border border-dashed border-[var(--chat-border)] px-6 py-16 text-center">
            <div className="text-base font-medium">拼团活动暂不可用</div>
            <Link
              to={ROUTES.PRICING}
              className="mt-4 inline-flex rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white"
            >
              前往直接购买
            </Link>
          </div>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1.1fr_0.9fr]">
            <section
              className={`overflow-hidden rounded-3xl border border-[var(--chat-border)] bg-gradient-to-br ${theme.gradient} shadow-[var(--shadow-md)]`}
            >
              <div className="p-8">
                <div className="flex items-center gap-3">
                  <div
                    className={`flex h-12 w-12 items-center justify-center rounded-2xl text-white ${theme.accent}`}
                  >
                    <Crown className="h-6 w-6" />
                  </div>
                  <div>
                    <h2 className="text-2xl font-semibold">{skuDisplayName(selectedSku)}</h2>
                    <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
                      {skuDescription(selectedSku)}
                    </p>
                  </div>
                </div>

                <div className="mt-8 flex items-end gap-3">
                  <div className="text-4xl font-semibold tracking-tight">
                    {formatPrice(groupPrice)}
                  </div>
                  {originPrice != null && groupPrice != null && originPrice > groupPrice ? (
                    <div className="pb-1 text-sm text-[var(--chat-text-soft)] line-through">
                      {formatPrice(originPrice)}
                    </div>
                  ) : null}
                </div>
                <div className="mt-2 flex flex-wrap gap-2 text-sm">
                  {selectedSku.periodQuota != null ? (
                    <span
                      className={`rounded-full px-3 py-1 ${theme.accentSoft} ${theme.accentText}`}
                    >
                      含 {formatQuota(selectedSku.periodQuota)}
                    </span>
                  ) : null}
                  {selectedSku.memberDays != null ? (
                    <span className="rounded-full bg-[var(--chat-surface-soft)] px-3 py-1 text-[var(--chat-text-soft)]">
                      有效期 {selectedSku.memberDays} 天
                    </span>
                  ) : null}
                </div>

                <div className="mt-8 grid gap-3 sm:grid-cols-3">
                  {[
                    {
                      icon: Zap,
                      title: '立即开通',
                      desc: '成团后自动生效',
                    },
                    {
                      icon: ShieldCheck,
                      title: '权益保障',
                      desc: '支付安全可追溯',
                    },
                    {
                      icon: Sparkles,
                      title: '拼团优惠',
                      desc: '人数达标享优惠价',
                    },
                  ].map((item) => {
                    const Icon = item.icon;
                    return (
                      <div
                        key={item.title}
                        className="rounded-2xl border border-white/60 bg-white/70 px-4 py-4 backdrop-blur-sm dark:bg-white/10"
                      >
                        <Icon className={`h-4 w-4 ${theme.accentText}`} />
                        <div className="mt-2 text-sm font-medium">{item.title}</div>
                        <div className="mt-1 text-xs text-[var(--chat-text-soft)]">{item.desc}</div>
                      </div>
                    );
                  })}
                </div>

                {ladder.length > 0 ? (
                  <div className="mt-6 overflow-hidden rounded-2xl border border-white/60 bg-white/70 backdrop-blur-sm dark:bg-white/10">
                    <div className="flex items-center justify-between border-b border-white/60 px-4 py-2.5 text-sm font-medium">
                      <span>额度阶梯</span>
                      <span className={`inline-flex items-center gap-1 text-xs ${theme.accentText}`}>
                        人数越多额度越高
                        <TrendingUp className="h-3.5 w-3.5" />
                      </span>
                    </div>
                    <div className="divide-y divide-white/50">
                      {ladder.map((row) => (
                        <div
                          key={row.label}
                          className="grid grid-cols-[1fr_auto_auto] items-center gap-2 px-4 py-2 text-sm"
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
                    <div className="px-4 py-2 text-[11px] text-[var(--chat-text-soft)]">
                      未满目标人数时，按已达档位结算额度
                    </div>
                  </div>
                ) : null}
              </div>
            </section>

            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-md)]">
              <div className="text-base font-medium">选择套餐</div>
              <div className="mt-3 space-y-2">
                {memberSkus.map((sku) => {
                  const active = sku.code === selectedSku.code;
                  return (
                    <button
                      key={sku.code}
                      type="button"
                      onClick={() => setSelectedCode(sku.code)}
                      className={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left transition ${
                        active
                          ? `border-transparent ring-2 ${skuTheme(sku.code).ring}`
                          : 'border-[var(--chat-border)] hover:bg-[var(--chat-surface-soft)]'
                      }`}
                    >
                      <div>
                        <div className="font-medium">{skuDisplayName(sku)}</div>
                        <div className="mt-0.5 text-xs text-[var(--chat-text-soft)]">
                          {formatQuota(sku.periodQuota)} · {sku.memberDays} 天
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{formatPrice(sku.price)}</span>
                        {active ? <Check className="h-4 w-4 text-violet-600" /> : null}
                      </div>
                    </button>
                  );
                })}
              </div>

              <div className="mt-6 rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4 text-sm">
                <div className="font-medium">拼团须知</div>
                {tiered ? (
                  <ul className="mt-2 space-y-1.5 text-[var(--chat-text-soft)]">
                    <li>· 每位成员支付同一价格，拼团人数越多，每人可得额度越高。</li>
                    <li>· 达到更高人数档位时额度按档位升级；未满目标按已达档位结算。</li>
                    <li>· 也可选择直接购买，按单独购买档位额度立即生效。</li>
                  </ul>
                ) : (
                  <ul className="mt-2 space-y-1.5 text-[var(--chat-text-soft)]">
                    <li>· 拼团支付成功后需等待成团，未成团将按规则处理。</li>
                    <li>· 成团后自动开通 Pro 会员并发放周期配额。</li>
                    <li>· 也可选择直接购买，支付后立即生效。</li>
                  </ul>
                )}
              </div>

              <div className="mt-6 space-y-3">
                <button
                  type="button"
                  onClick={() => void handleGroupStart()}
                  disabled={Boolean(buyingKey)}
                  className={`inline-flex w-full items-center justify-center gap-2 rounded-full px-4 py-3 text-sm font-medium text-white disabled:opacity-60 ${theme.accent}`}
                >
                  {buyingKey === `${selectedSku.code}-group` ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <UserPlus className="h-4 w-4" />
                  )}
                  <span>发起拼团 {formatPrice(groupPrice)}</span>
                </button>
                <button
                  type="button"
                  onClick={() => void handleDirectBuy()}
                  disabled={Boolean(buyingKey)}
                  className="inline-flex w-full items-center justify-center gap-2 rounded-full border border-[var(--chat-border)] px-4 py-3 text-sm font-medium disabled:opacity-60"
                >
                  {buyingKey === `${selectedSku.code}-direct` ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Zap className="h-4 w-4" />
                  )}
                  <span>直接购买 {formatPrice(selectedSku.price)}</span>
                </button>
              </div>

              <div className="mt-4 flex items-center gap-2 text-xs text-[var(--chat-text-soft)]">
                <Clock3 className="h-3.5 w-3.5" />
                支付跳转支付宝沙箱，完成后请到订单记录查看状态
              </div>
            </section>
          </div>
        )}

        {!loading && groupBuy?.teamList && groupBuy.teamList.length > 0 && selectedSku ? (
          <section className="mt-8">
            <h3 className="mb-4 text-lg font-medium">可加入的拼团</h3>
            <div className="grid gap-4 md:grid-cols-2">
              {groupBuy.teamList.map((team) => (
                <GroupTeamCard
                  key={team.teamId}
                  team={team}
                  sku={selectedSku}
                  groupPrice={groupBuy.goods?.payPrice}
                  buyingKey={buyingKey}
                  onJoin={handleJoinTeam}
                />
              ))}
            </div>
          </section>
        ) : null}
      </main>

      <PaymentQrDialog
        payment={qrPayment}
        onClose={closeQrPayment}
        onPaid={() => {
          closeQrPayment();
          navigate(`${ROUTES.PRICING}?tab=orders`);
        }}
      />
    </div>
  );
});

GroupBuyPage.displayName = 'GroupBuyPage';

export default GroupBuyPage;
