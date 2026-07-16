import { memo, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock3, History, Loader2, ShoppingBag, Users } from 'lucide-react';
import ShellNav from '@/components/ShellNav';
import QuotaStatusCard from '@/components/trade/QuotaStatusCard';
import QuotaOverview from '@/components/trade/QuotaOverview';
import { bffApi, type AccountSummary } from '@/services/bff';
import { ROUTES } from '@/router/routes';
import { AppearanceSettings } from '@/theme';
import { formatMicroQuota } from '@/utils/tradeDisplay';

const ledgerTypeLabel: Record<string, string> = {
  GRANT: '额度到账',
  MONTHLY_GRANT: '月度重置',
  FREEZE: '调用预留',
  CONFIRM: '调用结算',
  RELEASE: '释放预留',
  ADMIN_ADJUST: '管理员调整',
  REVOKE: '异常处理',
};

const AccountPage = memo(() => {
  const [loading, setLoading] = useState(true);
  const [summary, setSummary] = useState<AccountSummary | null>(null);

  useEffect(() => {
    bffApi
      .getAccountSummary()
      .then((data) => setSummary(data))
      .catch((error) => console.error('加载额度信息失败', error))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="min-h-screen bg-[var(--page-gradient)] text-foreground">
      <ShellNav />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h1 className="font-[family-name:var(--font-display)] text-3xl font-normal tracking-tight">
              额度中心
            </h1>
            <p className="mt-1 text-sm text-[var(--chat-text-soft)]">
              查看免费额度、永久付费额度与拼团进度
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Link
              to={ROUTES.PRICING}
              className="inline-flex items-center gap-2 rounded-full border border-[var(--chat-border)] bg-white/70 px-4 py-2 text-sm font-medium shadow-[var(--shadow-sm)] backdrop-blur-sm"
            >
              <ShoppingBag className="h-4 w-4" />
              购买套餐
            </Link>
            <Link
              to={ROUTES.GROUP_BUY_HALL}
              className="inline-flex items-center gap-2 rounded-full bg-[var(--chat-text)] px-4 py-2 text-sm font-medium text-white"
            >
              <Users className="h-4 w-4" />
              拼团大厅
            </Link>
          </div>
        </div>

        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-[var(--chat-text-soft)]" />
          </div>
        ) : !summary ? (
          <div className="rounded-3xl border border-dashed border-[var(--chat-border)] px-6 py-16 text-center text-sm text-[var(--chat-text-soft)]">
            暂无额度信息
          </div>
        ) : (
          <div className="space-y-6">
            <QuotaStatusCard summary={summary} />
            <QuotaOverview summary={summary} />

            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]">
              <div className="mb-4 flex items-center gap-2 text-base font-medium">
                <History className="h-4 w-4" />
                最近额度流水
              </div>
              {(summary.quotaLedger?.length ?? 0) === 0 ? (
                <div className="rounded-2xl border border-dashed border-[var(--chat-border)] px-4 py-8 text-center text-sm text-[var(--chat-text-soft)]">
                  暂无额度流水
                </div>
              ) : (
                <div className="divide-y divide-[var(--chat-border)]">
                  {summary.quotaLedger?.slice(0, 12).map((entry, index) => (
                    <div
                      key={entry.id ?? `${entry.createdAt}-${index}`}
                      className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm"
                    >
                      <div>
                        <div className="font-medium">
                          {ledgerTypeLabel[(entry.type || '').toUpperCase()] ||
                            entry.type ||
                            '额度变动'}
                        </div>
                        <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                          {[entry.abilityCode, entry.remark].filter(Boolean).join(' · ') || '—'}
                        </div>
                      </div>
                      <div className="text-right">
                        <div
                          className={
                            Number(entry.amount ?? 0) < 0 ? 'text-amber-700' : 'text-emerald-700'
                          }
                        >
                          {Number(entry.amount ?? 0) > 0 ? '+' : ''}
                          {formatMicroQuota(entry.amount ?? 0)}
                        </div>
                        <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                          {entry.createdAt || '-'}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            {(summary.pendingGroupOrders?.length ?? 0) > 0 ? (
              <section className="rounded-3xl border border-amber-200/80 bg-amber-50/70 p-6 shadow-[var(--shadow-sm)]">
                <div className="mb-4 flex items-center gap-2 text-base font-medium text-amber-900">
                  <Clock3 className="h-4 w-4" />
                  待成团订单
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  {summary.pendingGroupOrders?.map((order) => (
                    <article
                      key={order.orderId}
                      className="rounded-2xl border border-amber-200/70 bg-white/80 px-4 py-4"
                    >
                      <div className="font-medium">{order.productName || '额度订单'}</div>
                      <div className="mt-1 text-xs text-[var(--chat-text-soft)]">
                        订单号 {order.orderId}
                      </div>
                      <div className="mt-3 inline-flex rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                        {order.status || '等待成团'}
                      </div>
                      {order.paidAt ? (
                        <div className="mt-2 text-xs text-[var(--chat-text-soft)]">
                          支付时间 {order.paidAt}
                        </div>
                      ) : null}
                    </article>
                  ))}
                </div>
                <p className="mt-4 text-sm text-amber-800/80">
                  拼团支付成功仅锁定名额，成团后才会发放基础额度和当前档位赠送额度。
                </p>
              </section>
            ) : null}

            <section className="rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]">
              <div className="text-base font-medium">额度说明</div>
              <div className="mt-4 grid gap-3 text-sm text-[var(--chat-text-soft)] sm:grid-cols-3">
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">每月免费额度</div>
                  <div className="mt-2">每月重置为 5 点，未使用部分月底清零，不累计。</div>
                </div>
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">永久付费额度</div>
                  <div className="mt-2">直购支付成功立即到账，购买和拼团所得额度均永久有效。</div>
                </div>
                <div className="rounded-2xl bg-[var(--chat-surface-soft)] px-4 py-4">
                  <div className="font-medium text-[var(--chat-text)]">拼团购买</div>
                  <div className="mt-2">
                    拼团与直购同价，成团人数越多赠送越多；急用时可直接购买立即到账。
                  </div>
                </div>
              </div>
            </section>
          </div>
        )}

        <div className="mt-6">
          <AppearanceSettings />
        </div>
      </main>
    </div>
  );
});

AccountPage.displayName = 'AccountPage';

export default AccountPage;
