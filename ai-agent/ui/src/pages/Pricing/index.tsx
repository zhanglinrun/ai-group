import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Loader2, RefreshCw, ShoppingBag, Sparkles, Users, Wallet } from 'lucide-react';
import { message } from 'antd';
import ShellNav from '@/components/ShellNav';
import StatCard from '@/components/trade/StatCard';
import PackageCard from '@/components/trade/PackageCard';
import GroupPreviewDialog from '@/components/trade/GroupPreviewDialog';
import {
  bffApi,
  type AccountSummary,
  type GroupBuyInfo,
  type OrderItem,
  type SkuItem,
} from '@/services/bff';
import { payApi } from '@/services/pay';
import { useTradePurchase } from '@/hooks/useTradePurchase';
import {
  summarizeTradeWorkspace,
  tradeOrderStatusLabel,
  tradeSettlementHint,
} from '@/utils/tradeWorkspace';
import { paymentOutcomeMessage, resolvePaymentOutcome } from '@/utils/paymentStatus';
import { submitAlipayForm } from '@/utils/payForm';
import { ROUTES } from '@/router/routes';
import { isMemberSku, isTopupSku } from '@/utils/tradeDisplay';

type TradeTab = 'packages' | 'orders';

const EmptyLine = memo(({ text }: { text: string }) => (
  <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-8 text-center text-sm text-[var(--chat-text-soft)]">
    {text}
  </div>
));
EmptyLine.displayName = 'EmptyLine';

const hintToneClass = (tone: string) => {
  if (tone === 'warn') return 'text-amber-700';
  if (tone === 'ready') return 'text-emerald-700';
  if (tone === 'danger') return 'text-red-700';
  return 'text-[var(--chat-text-soft)]';
};

const PricingPage = memo(() => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [skus, setSkus] = useState<SkuItem[]>([]);
  const [groupBuy, setGroupBuy] = useState<GroupBuyInfo | null>(null);
  const [summary, setSummary] = useState<AccountSummary | null>(null);
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [groupPreviewSku, setGroupPreviewSku] = useState<SkuItem | null>(null);
  const [groupTeamsLoading, setGroupTeamsLoading] = useState(false);
  const [paymentReturned, setPaymentReturned] = useState(false);
  const { buyingKey, handleBuy } = useTradePurchase(groupBuy);

  const activeTab: TradeTab = searchParams.get('tab') === 'orders' ? 'orders' : 'packages';
  const paymentReturn =
    searchParams.get('paymentReturn') === '1' || searchParams.get('payment') === 'success';

  const workspace = useMemo(() => summarizeTradeWorkspace(summary, orders), [summary, orders]);

  const paymentMessage = useMemo(() => {
    if (!paymentReturned || !summary) return null;
    return paymentOutcomeMessage(resolvePaymentOutcome(summary));
  }, [paymentReturned, summary]);

  const loadTradeData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [pricing, orderList, accountSummary] = await Promise.all([
        bffApi.getPricing(),
        bffApi.getOrders(),
        bffApi.getAccountSummary(),
      ]);
      setSkus(pricing?.skus || []);
      setGroupBuy(pricing?.groupBuy || null);
      setOrders(orderList?.items || []);
      setSummary(accountSummary);
    } catch (nextError) {
      console.error('加载交易数据失败', nextError);
      setError('交易数据读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTradeData();
  }, [loadTradeData]);

  useEffect(() => {
    if (!paymentReturn) return;
    setPaymentReturned(true);
    // 支付宝同步回跳会在 return_url 上附带 out_trade_no：
    // 先调 sync_settle 主动查单结算（本地环境异步 notify 打不到，靠它完成开通闭环），再刷新数据。
    const outTradeNo = searchParams.get('out_trade_no');
    setSearchParams({ tab: 'orders' }, { replace: true });
    const settleThenReload = async () => {
      if (outTradeNo) {
        try {
          await payApi.syncSettle(outTradeNo);
        } catch (err) {
          console.error('支付回跳结算失败', err);
        }
      }
      await loadTradeData();
    };
    void settleThenReload();
  }, [paymentReturn, loadTradeData, searchParams, setSearchParams]);

  const switchTab = useCallback(
    (tab: TradeTab) => {
      setSearchParams({ tab });
    },
    [setSearchParams],
  );

  const refreshGroupTeams = useCallback(async (activityId: number) => {
    setGroupTeamsLoading(true);
    try {
      const data = await bffApi.getGroupBuy(activityId);
      setGroupBuy(data?.groupBuy || null);
    } catch (nextError) {
      console.error('加载拼团队伍失败', nextError);
      message.error('拼团信息读取失败');
    } finally {
      setGroupTeamsLoading(false);
    }
  }, []);

  const openGroupPreview = useCallback(
    async (sku: SkuItem) => {
      const activityId = sku.groupActivityId ?? groupBuy?.activityId;
      if (!activityId) {
        message.error('当前没有可用拼团活动');
        return;
      }
      setGroupPreviewSku(sku);
      await refreshGroupTeams(activityId);
    },
    [groupBuy?.activityId, refreshGroupTeams],
  );

  const handleDirectBuy = useCallback(
    async (sku: SkuItem) => {
      const ok = await handleBuy(sku, 'direct');
      if (ok) switchTab('orders');
    },
    [handleBuy, switchTab],
  );

  const handleGroupBuy = useCallback(
    async (sku: SkuItem, teamId?: string) => {
      const ok = await handleBuy(sku, 'group', teamId);
      if (ok) {
        setGroupPreviewSku(null);
        switchTab('orders');
      }
    },
    [handleBuy, switchTab],
  );

  // 待支付订单恢复支付：复用 pay 服务持久化的收银台表单，重新打开支付宝页面
  const handleResumePay = useCallback((order: OrderItem) => {
    if (!order.payUrl) {
      message.error('该订单暂无可用支付链接，请重新下单');
      return;
    }
    try {
      submitAlipayForm(order.payUrl);
    } catch (err) {
      console.error('恢复支付失败', err);
      message.error('打开支付页面失败，请稍后重试');
    }
  }, []);

  const memberSkus = useMemo(
    () => skus.filter((sku) => isMemberSku(sku) && sku.code !== 'FREE'),
    [skus],
  );
  const topupSkus = useMemo(() => skus.filter(isTopupSku), [skus]);
  const canGroupBuy = groupBuy?.activityId != null;

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="flex flex-col gap-4 rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-md)] lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h1 className="font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              {activeTab === 'packages' ? '购买中心' : '订单与到账'}
            </h1>
            <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
              {activeTab === 'packages'
                ? '支持直接购买与拼团购买，会员套餐与额度包一站选购。'
                : '查看支付状态、拼团进度与配额到账情况。'}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="inline-flex rounded-full border border-[var(--chat-border)] bg-[var(--chat-surface)]/70 p-1">
              <button
                type="button"
                onClick={() => switchTab('packages')}
                className={
                  activeTab === 'packages'
                    ? 'rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white'
                    : 'rounded-full px-4 py-2 text-sm text-[var(--chat-text-soft)]'
                }
              >
                购买套餐
              </button>
              <button
                type="button"
                onClick={() => switchTab('orders')}
                className={
                  activeTab === 'orders'
                    ? 'rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm text-white'
                    : 'rounded-full px-4 py-2 text-sm text-[var(--chat-text-soft)]'
                }
              >
                订单记录
              </button>
            </div>
            {activeTab === 'packages' && canGroupBuy ? (
              <Link
                to={ROUTES.GROUP_BUY_HALL}
                className="inline-flex items-center gap-2 rounded-full border border-violet-200 bg-violet-50 px-4 py-2 text-sm font-medium text-violet-700"
              >
                <Users className="h-4 w-4" />
                拼团大厅
              </Link>
            ) : null}
            <button
              type="button"
              onClick={() => void loadTradeData()}
              disabled={loading}
              className="inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm text-[var(--chat-text-soft)] disabled:opacity-60"
            >
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </button>
            <Link
              to={ROUTES.CHAT}
              className="inline-flex items-center rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm text-[var(--chat-text-soft)]"
            >
              返回对话
            </Link>
          </div>
        </div>

        {error ? (
          <div className="mt-6 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        ) : null}

        <div className="mt-6 grid gap-4 md:grid-cols-4">
          <StatCard label="可用配额" value={`${workspace.availableQuota} 点`} />
          <StatCard label="周期配额余额" value={`${workspace.periodQuota} 点`} />
          <StatCard label="拼团订单" value={`${workspace.groupOrders} 单`} />
          <StatCard
            label="待成团"
            value={`${workspace.waitingGroupOrders} 单`}
            warn={workspace.waitingGroupOrders > 0}
          />
        </div>

        {workspace.consistencyHints.length > 0 && (
          <div className="mt-4 space-y-2">
            {workspace.consistencyHints.map((hint) => (
              <div
                key={hint}
                className="rounded-2xl border border-[var(--chat-border)] bg-[var(--chat-surface-soft)] px-4 py-3 text-sm text-[var(--chat-text-soft)]"
              >
                {hint}
              </div>
            ))}
          </div>
        )}

        {activeTab === 'packages' ? (
          <div className="mt-6 space-y-8">
            <section>
              <div className="mb-4 flex items-center gap-2">
                <Sparkles className="h-4 w-4 text-violet-600" />
                <h2 className="text-lg font-medium">会员套餐</h2>
              </div>
              <div className="mb-4 rounded-2xl border border-violet-100 bg-violet-50/60 px-4 py-3 text-sm text-violet-900">
                同一套餐支持两种方式：
                <span className="font-medium">直接购买</span> 支付后立即开通；
                <span className="font-medium">拼团购买</span> 享优惠价格，成团后自动发放权益。
              </div>
              {loading ? (
                <div className="flex justify-center py-16">
                  <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
                </div>
              ) : memberSkus.length === 0 ? (
                <EmptyLine text="暂无可用会员套餐" />
              ) : (
                <div className="grid gap-4 md:grid-cols-2">
                  {memberSkus.map((sku, index) => (
                    <PackageCard
                      key={sku.code}
                      sku={sku}
                      highlight={index === 0}
                      groupPrice={sku.groupPayPrice ?? groupBuy?.goods?.payPrice}
                      deductionPrice={sku.groupDeductionPrice ?? groupBuy?.goods?.deductionPrice}
                      buyingKey={buyingKey}
                      onDirectBuy={() => void handleDirectBuy(sku)}
                      onGroupBuy={
                        sku.groupActivityId != null || canGroupBuy
                          ? () => void openGroupPreview(sku)
                          : undefined
                      }
                    />
                  ))}
                </div>
              )}
            </section>

            <section>
              <div className="mb-4 flex items-center gap-2">
                <Wallet className="h-4 w-4 text-emerald-600" />
                <h2 className="text-lg font-medium">额度包</h2>
              </div>
              <p className="mb-4 text-sm text-[var(--chat-text-soft)]">
                单独购买加油包额度，支付成功后立即到账，可与会员周期配额叠加使用；也支持拼团购买享优惠价。
              </p>
              {loading ? (
                <div className="flex justify-center py-10">
                  <Loader2 className="h-6 w-6 animate-spin text-[var(--chat-text-soft)]" />
                </div>
              ) : topupSkus.length === 0 ? (
                <EmptyLine text="暂无额度包商品" />
              ) : (
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  {topupSkus.map((sku) => (
                    <PackageCard
                      key={sku.code}
                      sku={sku}
                      groupPrice={sku.groupPayPrice}
                      deductionPrice={sku.groupDeductionPrice}
                      buyingKey={buyingKey}
                      onDirectBuy={() => void handleDirectBuy(sku)}
                      onGroupBuy={
                        sku.groupActivityId != null ? () => void openGroupPreview(sku) : undefined
                      }
                    />
                  ))}
                </div>
              )}
            </section>

            {skus.some((sku) => sku.code === 'FREE') && (
              <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-4 text-sm text-[var(--chat-text-soft)]">
                <ShoppingBag className="mb-2 h-4 w-4" />
                Free 套餐注册即享，无需购买。
              </div>
            )}
          </div>
        ) : (
          <div className="mt-6 grid gap-6 lg:grid-cols-[1.2fr_0.8fr]">
            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]">
              <div className="mb-4 flex items-center justify-between">
                <div className="text-base font-medium">最近订单</div>
                <div className="text-sm text-[var(--chat-text-soft)]">
                  {workspace.totalOrders} 单
                </div>
              </div>
              {paymentMessage && (
                <div className="mb-4 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
                  {paymentMessage}
                </div>
              )}
              {loading ? (
                <div className="flex justify-center py-12">
                  <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
                </div>
              ) : workspace.recentOrders.length === 0 ? (
                <EmptyLine text="暂无订单，购买或参与拼团后这里会显示进度。" />
              ) : (
                <div className="space-y-3">
                  {workspace.recentOrders.map((order) => {
                    const hint = tradeSettlementHint(order);
                    return (
                      <article
                        key={order.orderId}
                        className="rounded-2xl border border-[var(--chat-border)] bg-white/60 p-4 dark:bg-white/5"
                      >
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <div className="truncate text-sm font-medium">
                              {order.productName || '会员订单'}
                            </div>
                            <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                              {order.orderId}
                            </div>
                          </div>
                          <div className="text-right">
                            <div className="text-sm font-medium">
                              {order.amount != null ? `¥${order.amount}` : '-'}
                            </div>
                            <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                              {tradeOrderStatusLabel(
                                order.status,
                                order.groupStatus,
                                order.displayStatus,
                              )}
                            </div>
                          </div>
                        </div>
                        <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                          <div
                            className={`inline-flex rounded-full bg-[var(--chat-surface-soft)] px-3 py-1 text-xs ${hintToneClass(hint.tone)}`}
                          >
                            {hint.label}：{hint.detail}
                          </div>
                          {order.displayStatus === 'PAY_WAIT' && order.payUrl ? (
                            <button
                              type="button"
                              onClick={() => handleResumePay(order)}
                              className="inline-flex items-center rounded-full bg-[var(--primary)] px-4 py-1.5 text-xs font-medium text-white transition-opacity hover:opacity-90"
                            >
                              去支付
                            </button>
                          ) : null}
                        </div>
                      </article>
                    );
                  })}
                </div>
              )}
            </section>

            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]">
              <div className="mb-4 text-base font-medium">会员状态</div>
              {summary ? (
                <div className="space-y-3 text-sm">
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">等级</span>
                    <span>{summary.tier || 'FREE'}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">可用配额</span>
                    <span>{summary.availableQuota ?? summary.periodQuotaBalance ?? 0} 点</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">加油包余额</span>
                    <span>{summary.topupQuotaBalance ?? 0} 点</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">冻结配额</span>
                    <span>{summary.frozenBalance ?? 0} 点</span>
                  </div>
                  {summary.expireAt && (
                    <div className="flex justify-between">
                      <span className="text-[var(--chat-text-soft)]">到期时间</span>
                      <span>{summary.expireAt}</span>
                    </div>
                  )}
                  {(summary.pendingGroupOrders?.length ?? 0) > 0 && (
                    <div className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                      有 {summary.pendingGroupOrders?.length} 笔订单等待成团
                    </div>
                  )}
                </div>
              ) : (
                <EmptyLine text="会员信息加载中..." />
              )}
              <Link
                to={ROUTES.ACCOUNT}
                className="mt-5 inline-flex rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm text-[var(--chat-text-soft)]"
              >
                查看会员中心
              </Link>
            </section>
          </div>
        )}
      </main>

      {groupPreviewSku && groupBuy ? (
        <GroupPreviewDialog
          sku={groupPreviewSku}
          groupBuy={groupBuy}
          loading={groupTeamsLoading}
          buyingKey={buyingKey}
          onClose={() => setGroupPreviewSku(null)}
          onBuy={(teamId) => void handleGroupBuy(groupPreviewSku, teamId)}
        />
      ) : null}
    </div>
  );
});

PricingPage.displayName = 'PricingPage';

export default PricingPage;
