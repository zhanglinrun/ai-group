import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Loader2, RefreshCw, Users, Wallet } from 'lucide-react';
import { message } from 'antd';
import ShellNav from '@/components/ShellNav';
import StatCard from '@/components/trade/StatCard';
import PackageCard from '@/components/trade/PackageCard';
import GroupPreviewDialog from '@/components/trade/GroupPreviewDialog';
import PaymentQrDialog from '@/components/trade/PaymentQrDialog';
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
import { formatMicroQuota } from '@/utils/tradeDisplay';

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
  const { buyingKey, handleBuy, qrPayment, closeQrPayment } = useTradePurchase(groupBuy);

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
      if (tab === 'orders') {
        void loadTradeData();
      }
    },
    [loadTradeData, setSearchParams],
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
      // 打开支付二维码；支付到账后由弹窗 onPaid 切到订单页并刷新
      await handleBuy(sku, 'direct');
    },
    [handleBuy],
  );

  const handleGroupBuy = useCallback(
    async (sku: SkuItem, teamId?: string) => {
      const ok = await handleBuy(sku, 'group', teamId);
      if (ok) {
        setGroupPreviewSku(null);
      }
    },
    [handleBuy],
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

  const quotaSkus = useMemo(() => skus.filter((sku) => (sku.baseQuota ?? 0) > 0), [skus]);
  const canGroupBuy = groupBuy?.activityId != null;

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="flex flex-col gap-4 rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-md)] lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h1 className="font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              {activeTab === 'packages' ? '购买额度' : '订单与到账'}
            </h1>
            <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
              {activeTab === 'packages'
                ? '直购与拼团同价；直购立即到账，拼团人数越多赠送额度越多。'
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
          <StatCard label="可用额度" value={formatMicroQuota(workspace.availableQuota)} />
          <StatCard label="本月免费" value={formatMicroQuota(workspace.freeQuota)} />
          <StatCard label="永久付费" value={formatMicroQuota(workspace.paidQuota)} />
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
                <Wallet className="h-4 w-4 text-emerald-600" />
                <h2 className="text-lg font-medium">额度包</h2>
              </div>
              <div className="mb-4 rounded-2xl border border-violet-100 bg-violet-50/60 px-4 py-3 text-sm text-violet-900">
                <span className="font-medium">直接购买</span> 支付成功后立即到账；
                <span className="font-medium">拼团购买</span>{' '}
                同价，成团后按人数赠送额度。付费额度永久有效。
              </div>
              {loading ? (
                <div className="flex justify-center py-16">
                  <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
                </div>
              ) : quotaSkus.length === 0 ? (
                <EmptyLine text="暂无额度包商品" />
              ) : (
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                  {quotaSkus.map((sku, index) => (
                    <PackageCard
                      key={sku.code}
                      sku={sku}
                      highlight={index === 1}
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
            <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-4 text-sm text-[var(--chat-text-soft)]">
              每位用户每月另享 5 点免费额度，月底清零；消费时优先扣免费额度。
            </div>
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
                              {order.productName || '额度订单'}
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
              <div className="mb-4 text-base font-medium">额度余额</div>
              {summary ? (
                <div className="space-y-3 text-sm">
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">本月免费</span>
                    <span>{formatMicroQuota(summary.freeQuotaBalance)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">可用配额</span>
                    <span>{formatMicroQuota(summary.availableQuota)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">永久付费</span>
                    <span>{formatMicroQuota(summary.paidQuotaBalance)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-[var(--chat-text-soft)]">冻结配额</span>
                    <span>{formatMicroQuota(summary.frozenBalance)}</span>
                  </div>
                  {(summary.pendingGroupOrders?.length ?? 0) > 0 && (
                    <div className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                      有 {summary.pendingGroupOrders?.length} 笔订单等待成团
                    </div>
                  )}
                </div>
              ) : (
                <EmptyLine text="额度信息加载中..." />
              )}
              <Link
                to={ROUTES.ACCOUNT}
                className="mt-5 inline-flex rounded-full border border-[var(--chat-border)] px-4 py-2 text-sm text-[var(--chat-text-soft)]"
              >
                查看额度中心
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

      <PaymentQrDialog
        payment={qrPayment}
        onClose={closeQrPayment}
        onPaid={() => {
          closeQrPayment();
          switchTab('orders');
          void loadTradeData();
        }}
      />
    </div>
  );
});

PricingPage.displayName = 'PricingPage';

export default PricingPage;
